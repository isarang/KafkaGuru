package com.loco.kafkaguru.controller;

import com.loco.kafkaguru.MessageFormatter;
import com.loco.kafkaguru.core.KafkaInstance;
import com.loco.kafkaguru.core.KafkaReader;
import com.loco.kafkaguru.core.PluginLoader;
import com.loco.kafkaguru.core.listeners.KafkaListener;
import com.loco.kafkaguru.model.KafkaClusterInfo;
import com.loco.kafkaguru.viewmodel.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
//import lombok.extern.log4j.Log4j2;
// import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.net.URL;
import java.util.*;
import java.util.function.Predicate;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

//@Log4j2
public class KafkaPaneController implements Initializable, KafkaListener {
    // UI controls
    @FXML
    private SplitPane topicsMessagesPane;
    @FXML
    private Accordion topicsAccordian;
    @FXML
    private TitledPane topicsPane;
    @FXML
    private TitledPane preferencesPane;
    @FXML
    private TreeView<AbstractNode> topicsTree;
    @FXML
    private CheckBox followSelectionCheck;
    @FXML
    private ComboBox<String> messageCountBox;
    @FXML
    private ComboBox<String> cursorBox;

    // messages toolbar
    @FXML
    private Button refreshButton;
    @FXML
    private TextField includeField;
    @FXML
    private TextField excludeField;

    // messages table
    @FXML
    private TableView<MessageModel> messagesTable;
    @FXML
    private TableColumn<MessageModel, Integer> rowNumberColumn;
    @FXML
    private TableColumn<MessageModel, Integer> partitionColumn;
    @FXML
    private TableColumn<MessageModel, Long> offsetColumn;
    @FXML
    private TableColumn<MessageModel, String> keyColumn;
    @FXML
    private TableColumn<MessageModel, String> messageSummaryColumn;
    @FXML
    private TableColumn<MessageModel, Date> timestampColumn;

    // message display
    @FXML
    private TextArea messageArea;

    // data fields
    private KafkaClusterInfo cluster;
    private KafkaInstance kafkaInstance;

    private Map<String, List<PartitionInfo>> topics;
    private KafkaReader kafkaReader;
    private ClusterNode clusterNode;
    private MessagesModel messagesModel;

    private ControllerListener parent;
    private Preferences preferences;
    private BooleanProperty followTreeSelection = new SimpleBooleanProperty(true);

    // This is the node which is currently associated with the messages table.
    private AbstractNode currentTopicNode;
    boolean currentNodeStale = false;
    private boolean loading = false;
    private int maxMessagesToFetch = 50;
    private long fetchFrom = -1;
    private boolean connected;
    private DoubleProperty topicMessageDividerPos;
    private String id;
    private ContextMenu topicContextMenu;

    public KafkaPaneController(KafkaClusterInfo cluster, ControllerListener parent, Preferences preferences) {
        id = preferences.name();
        this.parent = parent;
        this.preferences = preferences;
        if (StringUtils.isEmpty(cluster.getName())) {
            throw new IllegalArgumentException("name is not specified");
        }
        if (StringUtils.isEmpty(cluster.getUrl())) {
            throw new IllegalArgumentException("url is not specified");
        }

        this.cluster = cluster;
    }

    public String getId() {
        return id;
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        setupKafka();
        setupTopicsTree();
        setupMessagesView();
        setupPreferencesView();

        topicsAccordian.setExpandedPane(topicsPane);

        topicMessageDividerPos = topicsMessagesPane.getDividers().get(0).positionProperty();
        var lastDividerPos = preferences.getDouble("topic_message_divider", 0.1);
        topicMessageDividerPos.set(lastDividerPos);

        // TODO report connection error
        kafkaInstance.connectAsync(this);
    }

    private void setupPreferencesView() {
        followSelectionCheck.selectedProperty().bindBidirectional(followTreeSelection);

        setupMessageCountBox();
        setupCursorBox();
    }

    private void setupCursorBox() {
        this.cursorBox.setValue("End");
        cursorBox.valueProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observableValue, String oldValue, String newValue) {
                switch (newValue) {
                    case "Beginning":
                        fetchFrom = 0;
                        break;
                    case "End":
                        fetchFrom = -1;
                        break;
                    default:
                        try {
                            fetchFrom = Integer.parseInt(newValue);
                        } catch (NumberFormatException e) {
                            cursorBox.valueProperty().set(oldValue);
                        }
                        break;
                }
            }
        });
    }

    private void setupMessageCountBox() {
        messageCountBox.setValue("" + maxMessagesToFetch);
        messageCountBox.valueProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observableValue, String oldValue, String newValue) {
                try {
                    maxMessagesToFetch = Integer.parseInt(newValue);
                } catch (NumberFormatException e) {
                    messageCountBox.valueProperty().set(oldValue);
                }
            }
        });
    }

    private void removeClusterNode() {
        parent.destroy(this);
        var removeCluster = new Alert(Alert.AlertType.ERROR,
                "Failed to fetch topics." + "\\nWould you like to remove the following cluster from your saved list?"
                        + "\n\n Cluster Name: " + kafkaInstance.getName(),
                ButtonType.YES, ButtonType.NO).showAndWait();
        if (removeCluster.orElse(ButtonType.NO).equals(ButtonType.YES)) {
            try {
                preferences.removeNode();

            } catch (BackingStoreException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void setupMessagesView() {
        setupMessagesToolbar();

        rowNumberColumn.setCellValueFactory(new PropertyValueFactory<>("index"));
        partitionColumn.setCellValueFactory(new PropertyValueFactory<>("partition"));
        offsetColumn.setCellValueFactory(new PropertyValueFactory<>("offset"));
        keyColumn.setCellValueFactory(new PropertyValueFactory<>("key"));
        messageSummaryColumn.setCellValueFactory(new PropertyValueFactory<>("messageSummary"));
        timestampColumn.setCellValueFactory(new PropertyValueFactory<>("timestamp"));

        messagesTable.getSelectionModel().selectedItemProperty()
                .addListener((observableValue, oldMessage, newMessage) -> displayMessage(newMessage));
    }

    private void setupMessagesToolbar() {
        refreshButton.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent actionEvent) {
                messagesTable.requestFocus();
                refreshMessages();
            }
        });

        setupMessagesFilter();
    }

    private void refreshMessages() {
        currentTopicNode = topicsTree.getSelectionModel().getSelectedItem().getValue();
        fetchMessages(currentTopicNode);
    }

    private void setupMessagesFilter() {
        messagesModel = new MessagesModel();

        ObjectProperty<Predicate<MessageModel>> includeFilter = new SimpleObjectProperty<>();
        ObjectProperty<Predicate<MessageModel>> excludeFilter = new SimpleObjectProperty<>();

        includeFilter.bind(Bindings.createObjectBinding(() -> message -> {
            var filter = includeField.getText().toLowerCase();
            var body = message.getMessageBody().toLowerCase();
            return StringUtils.isEmpty(filter) || body.contains(filter);
        }, includeField.textProperty()));

        excludeFilter.bind(Bindings.createObjectBinding(() -> message -> {
            var filter = excludeField.getText().toLowerCase();
            var body = message.getMessageBody().toLowerCase();
            return StringUtils.isEmpty(filter) || !body.contains(filter);
        }, excludeField.textProperty()));

        FilteredList<MessageModel> filteredData = new FilteredList<>(messagesModel.getMessages());

        filteredData.predicateProperty().bind(Bindings
                .createObjectBinding(() -> includeFilter.get().and(excludeFilter.get()), includeFilter, excludeFilter));

        // 3. Wrap the FilteredList in a SortedList.
        SortedList<MessageModel> sortedData = new SortedList<>(filteredData);

        // 4. Bind the SortedList comparator to the TableView comparator.
        sortedData.comparatorProperty().bind(messagesTable.comparatorProperty());

        // 5. Add sorted (and filtered) data to the table.
        messagesTable.setItems(sortedData);
    }

    private void displayMessage(MessageModel message) {
        if (message != null) {
            messageArea.setText(message.getMessageBody());
        } else {
            messageArea.clear();
        }
    }

    private void setupTopicsTree() {
        var rootItem = new TreeItem<AbstractNode>(clusterNode);
        topicsTree.setRoot(rootItem);
        topicsTree.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends TreeItem<AbstractNode>> observableValue,
                    TreeItem<AbstractNode> oldItem, TreeItem<AbstractNode> newItem) {
                treeSelectionChanged(newItem);
            }
        });

        topicContextMenu = new ContextMenu();
        var headerItem = new MenuItem("Select Message Format");
        // headerItem.setDisable(true);
        topicContextMenu.getItems().add(headerItem);
        topicContextMenu.getItems().add(new SeparatorMenuItem());
        for (var name : PluginLoader.formatters.keySet()) {
            var menuItem = new RadioMenuItem(name);
            menuItem.setUserData(name);
            topicContextMenu.getItems().add(menuItem);
            menuItem.setOnAction(event -> {
                var item = (MenuItem) event.getSource();
                var formatterName = (String) item.getUserData();
                // log.info("Selected formatter " + formatterName);
                var formatter = PluginLoader.formatters.get(formatterName);

                TopicNode topicNode = getTopicNode(currentTopicNode);
                if (topicNode != null) {
                    // log.info("Selected topic " + topicNode.getTopic());
                    topicNode.setFormatter(formatter);
                    messagesModel.setMessages(currentTopicNode.getMessages());
                    saveFormatter(topicNode.getTopic(), formatter);
                } else {
                    // log.info("Selected topic is null");
                }
            });
        }
        topicsTree.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                TreeItem<AbstractNode> selected = topicsTree.getSelectionModel().getSelectedItem();

                // item is selected - this prevents fail when clicking on empty space
                if (selected != null) {
                    // open context menu on current screen position
                    openContextMenu(selected, e.getScreenX(), e.getScreenY());
                }
            } else {
                // any other click cause hiding menu
                topicContextMenu.hide();
            }
        });
    }

    private TopicNode getTopicNode(AbstractNode node) {
        TopicNode topicNode = null;
        if (node instanceof TopicNode) {
            topicNode = (TopicNode) node;
        } else if (node instanceof PartitionNode) {
            topicNode = (TopicNode) ((PartitionNode) node).getParent();
        }
        return topicNode;
    }

    private void openContextMenu(TreeItem<AbstractNode> treeItem, double x, double y) {
        // custom method that update menu items
        topicContextMenu.setUserData(treeItem.getValue());

        // show menu
        topicContextMenu.show(topicsTree, x, y);
        topicContextMenu.getItems().forEach(item -> {
            if (item instanceof RadioMenuItem) {
                var radioItem = (RadioMenuItem) item;
                radioItem.setSelected(false);
                // TODO use treeItem
                var node = getTopicNode(treeItem.getValue());
                if (item.getUserData() != null && node != null) {
                    if (radioItem.getUserData().equals(node.getFormatter().name())) {
                        radioItem.setSelected(true);
                    }
                }
            }
        });
    }

    private void setupKafka() {
        kafkaReader = new KafkaReader(cluster.getName(), cluster.getUrl());
        kafkaInstance = kafkaReader.getKafkaInstance();
        clusterNode = new ClusterNode(kafkaInstance);
    }

    private void treeSelectionChanged(TreeItem<AbstractNode> newItem) {
        if (!connected) {
            return;
        }
        if (newItem == null) {
            return;
        }

        if (followTreeSelection.get()) {
            var selectedNode = newItem.getValue();

            saveSelectionPreference(newItem);

            messagesModel.setMessages(selectedNode.getMessages());
            currentTopicNode = selectedNode;
            if (loading) {
                currentNodeStale = true;
            } else {
                fetchMessages(selectedNode);
            }
        }
    }

    private void saveSelectionPreference(TreeItem<AbstractNode> selectedTreeItem) {
        AbstractNode selectedNode = selectedTreeItem.getValue();

        var selectedTopic = "";
        var selectedPartition = -1;
        if (selectedNode instanceof TopicNode) {
            selectedTopic = ((TopicNode) selectedNode).getTopic();
        } else {
            var partitionNode = (PartitionNode) selectedNode;
            selectedPartition = partitionNode.getPartition().partition();
            selectedTopic = partitionNode.getPartition().topic();
        }

        preferences.put("selected_topic", selectedTopic);
        preferences.putInt("selected_partition", selectedPartition);
    }

    private void fetchMessages(AbstractNode node) {
        setLoadingStatus(true);
        try {
            var topicPartitions = getTopicPartitions(node);
            kafkaReader.getMessagesAsync(topicPartitions, maxMessagesToFetch, fetchFrom, KafkaPaneController.this,
                    node);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setLoadingStatus(boolean isLoading) {
        this.loading = isLoading;
        refreshButton.setDisable(isLoading);
    }

    private static List<MessageModel> createMessages(int startRow, List<ConsumerRecord<String, byte[]>> records,
            MessageFormatter formatter) {
        final var row = new Object() {
            public int value = startRow;
        };

        var messages = records.stream().map(record -> new MessageModel(++row.value, record, formatter))
                .collect(Collectors.toList());
        return messages;
    }

    @Override
    public void topicsUpdated(Map<String, List<PartitionInfo>> newTopics) {
        if (newTopics == null) {
            connected(false);
            return;
        }
        try {
            if (topics == null || !newTopics.keySet().equals(topics.keySet())) {
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        updateTopicsTree(newTopics);
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void messagesReceived(List<ConsumerRecord<String, byte[]>> records, Object sender, int batchNumber,
            boolean moreToCome) {
        // log.info("Received {} messages", records.size());
        Platform.runLater(() -> {
            // log.info("Processing {} messages", records.size());
            // update the sender node
            var senderNode = (AbstractNode) sender;
            var formatter = getFormatter(senderNode);
            if (batchNumber == 1) {
                var messages = createMessages(0, records, formatter);
                senderNode.setMessages(messages);
            } else {
                var messages = createMessages(senderNode.getMessages().size(), records, formatter);
                senderNode.addMessages(messages);
            }
            // log.info("Added {} messages to the node", records.size());

            setLoadingStatus(moreToCome);

            if (currentTopicNode == senderNode) {
                updateMessagesTable();
                // log.info("Added {} messages to the table", records.size());
            } else {
                if (currentNodeStale) {
                    fetchMessages(currentTopicNode);
                    currentNodeStale = false;
                } else {
                    new Alert(Alert.AlertType.WARNING, "currentTopicNode != senderNode, and currentNodeStale is false")
                            .showAndWait();
                }
            }
        });
    }

    private MessageFormatter getFormatter(AbstractNode senderNode) {
        var formatter = PluginLoader.defaultFormatter;
        var topicNode = getTopicNode(senderNode);
        if (topicNode != null) {
            formatter = topicNode.getFormatter();
        }
        return formatter;
    }

    private void updateMessagesTable() {
        var selectionModel = messagesTable.getSelectionModel();
        int selectedRow = selectionModel.getSelectedIndex();
        messagesModel.setMessages(currentTopicNode.getMessages());
        selectionModel.select(selectedRow);
        messagesTable.requestFocus();
    }

    @Override
    public void connected(boolean really) {
        if (really) {
            this.connected = true;
        } else {
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    removeClusterNode();
                }
            });
        }
    }

    private List<TopicPartition> getTopicPartitions(AbstractNode selectedNode) {
        List<TopicPartition> partitions = new ArrayList<>();

        if (selectedNode == null) {
        } else if (selectedNode instanceof TopicNode) {
            var topicNode = (TopicNode) selectedNode;
            partitions = topicNode.getTopicPartitions();
        } else if (selectedNode instanceof PartitionNode) {
            PartitionNode partitionNode = (PartitionNode) selectedNode;
            partitions.add(partitionNode.getTopicPartition());
        }

        return partitions;
    }

    private void updateTopicsTree(Map<String, List<PartitionInfo>> newTopics) {
        topicMessageDividerPos.removeListener(this::topicMessageDividerChanged);
        var rootNode = topicsTree.getRoot();
        topics = newTopics;
        rootNode.getChildren().clear();

        createTopicNodes(clusterNode, topics);
        var topicItems = createTopicItems(clusterNode);
        rootNode.getChildren().addAll(topicItems);

        rootNode.setExpanded(true);

        var lastSelectedTreeItem = getLastSelectedTreeItem(rootNode);
        if (lastSelectedTreeItem != null) {
            topicsTree.getSelectionModel().select(lastSelectedTreeItem);
        }

        var lastDividerPos = preferences.getDouble("topic_message_divider_pos", 0.1);
        topicMessageDividerPos.set(lastDividerPos);
        topicMessageDividerPos.addListener(this::topicMessageDividerChanged);
    }

    private TreeItem<AbstractNode> getLastSelectedTreeItem(TreeItem<AbstractNode> rootNode) {
        var topic = preferences.get("selected_topic", "");
        var topicNode = rootNode.getChildren().stream().filter(node -> {
            return topic.equals(((TopicNode) node.getValue()).getTopic());
        }).findFirst().orElse(null);

        if (topicNode == null)
            return null;

        var partition = preferences.getInt("selected_partition", -1);
        TreeItem<AbstractNode> partitionNode = null;

        if (partition >= 0) {
            partitionNode = topicNode.getChildren().stream()
                    .filter(node -> ((PartitionNode) node.getValue()).getPartition().partition() == partition)
                    .findFirst().orElse(null);
        }

        var selectedNode = partitionNode == null ? topicNode : partitionNode;
        return selectedNode;
    }

    private void createTopicNodes(ClusterNode clusterNode, Map<String, List<PartitionInfo>> topics) {
        var topicNodes = topics.entrySet().stream().map(entry -> createTopicNode(clusterNode, entry))
                .collect(Collectors.toList());

        clusterNode.setTopicNodes(topicNodes);
    }

    private TopicNode createTopicNode(ClusterNode clusterNode, Map.Entry<String, List<PartitionInfo>> entry) {
        var topicNode = new TopicNode(clusterNode, entry.getKey(), entry.getValue());
        // TODO remove partition node creation logic out of TopicNode class
        topicNode.setFormatter(getFormatter(topicNode.getTopic()));
        return topicNode;
    }

    private MessageFormatter getFormatter(String topic) {
        var nodes = new ArrayList<String>();
        nodes.add("topics");
        nodes.add(topic);

        var formatterName = parent.getPreference(nodes, "formatter");
        if (!StringUtils.isEmpty(formatterName)) {
            var formatter = PluginLoader.formatters.get(formatterName);
            if (formatter != null) {
                return formatter;
            }
        }
        return PluginLoader.defaultFormatter;
    }

    private void saveFormatter(String topic, MessageFormatter formatter) {
        var nodes = new ArrayList<String>();
        nodes.add("topics");
        nodes.add(topic);

        parent.savePreference(nodes, "formatter", formatter.name());
    }

    public void preferenceUpdated(ArrayList<String> nodeNames, String key, String value) {
        if (nodeNames.isEmpty()) {
            return;
        }
        nodeNames = new ArrayList<>(nodeNames);
        var nodeName = nodeNames.remove(0);
        switch (nodeName) {
            case "topics":
                var topic = nodeNames.remove(0);
                topicPreferenceUpdated(nodeNames, topic, key, value);
                break;
            default:
                break;
        }
    }

    private void topicPreferenceUpdated(ArrayList<String> nodeNames, String topic, String key, String value) {
        var topicNode = getTopicNode(topic);
        if (topicNode != null) {
            switch (key) {
                case "formatter":
                    var formatter = PluginLoader.formatters.get(value);
                    topicNode.setFormatter(formatter);
                    messagesModel.setMessages(currentTopicNode.getMessages());
                    break;
                default:
                    break;
            }
        }
    }

    private TopicNode getTopicNode(String topic) {
        var topicItem = topicsTree.getRoot().getChildren().stream().filter(item -> {
            var node = item.getValue();
            if (node instanceof TopicNode) {
                var topicName = ((TopicNode) node).getTopic();
                return topic.equals(topicName);
            }
            return false;
        }).findFirst();

        return topicItem.isEmpty() ? null : (TopicNode) topicItem.get().getValue();
    }

    private List<TreeItem<AbstractNode>> createTopicItems(ClusterNode clusterNode) {
        var topicNodes = clusterNode.getTopicNodes().stream().map(topicNode -> createTopicItem(topicNode))
                .collect(Collectors.toList());
        return topicNodes;
    }

    private TreeItem<AbstractNode> createTopicItem(TopicNode topicNode) {
        var topicItem = new TreeItem<AbstractNode>(topicNode);

        var partitionNodes = createPartitionItems(topicNode);
        topicItem.getChildren().addAll(partitionNodes);

        return topicItem;
    }

    private List<TreeItem<AbstractNode>> createPartitionItems(TopicNode topicNode) {
        return topicNode.getPartitions().stream().map(p -> new TreeItem<AbstractNode>(p)).collect(Collectors.toList());
    }

    private void topicMessageDividerChanged(ObservableValue<? extends Number> observable, Number oldValue,
            Number newValue) {
        preferences.putDouble("topic_message_divider_pos", newValue.doubleValue());
    }
}
