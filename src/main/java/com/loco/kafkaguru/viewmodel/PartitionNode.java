package com.loco.kafkaguru.viewmodel;

import com.loco.kafkaguru.MessageFormatter;
//import lombok.Data;
//import lombok.extern.log4j.Log4j2;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;

import java.util.List;

//@Data
// @Log4j2
public class PartitionNode implements AbstractNode {
    private final TopicPartition topicPartition;
    private AbstractNode parent;

    public PartitionInfo getPartition() {
        return partition;
    }

    private PartitionInfo partition;
    private final String name;

    @Override
    public List<MessageModel> getMessages() {
        return messages;
    }

    @Override
    public void setMessages(List<MessageModel> messages) {
        this.messages = messages;
    }

    private List<MessageModel> messages;
    private MessageFormatter formatter;

    public PartitionNode(TopicNode parent, PartitionInfo partition) {
        this.parent = parent;
        this.partition = partition;
        name = "Partition " + partition.partition();
        topicPartition = new TopicPartition(partition.topic(), partition.partition());
    }

    public String toString() {
        return name;
    }

    public boolean equals(Object other) {
        if (!(other instanceof PartitionNode)) {
            return false;
        }
        PartitionNode otherNode = (PartitionNode) other;
        if (partition != otherNode.getPartition()) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public void addMessages(List<MessageModel> messages) {
        this.messages.addAll(messages);
    }

    public void setFormatter(MessageFormatter formatter) {
        // log.info("In partition " + topicPartition.toString());
        // log.info("existing formatter " + (this.formatter == null ? "null" :
        // this.formatter.name()));
        // log.info("Setting formatter " + formatter.name());
        if (this.formatter != formatter) {
            this.formatter = formatter;
            reformatMessages();
        }
    }

    private void reformatMessages() {
        if (messages != null) {
            messages.forEach(msg -> msg.setFormatter(formatter));
        }
    }

    public MessageFormatter getFormatter() {
        return formatter;
    }

    public TopicPartition getTopicPartition() {
        return topicPartition;
    }

    public AbstractNode getParent() {
        return parent;
    }
}
