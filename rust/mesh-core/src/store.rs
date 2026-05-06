use std::collections::HashMap;

use crate::message::{Message, MessageId, NodeId};

#[derive(Debug, Default)]
pub struct PendingStore {
    messages: HashMap<NodeId, Vec<Message>>,
}

impl PendingStore {
    pub fn new() -> Self {
        Self {
            messages: HashMap::new(),
        }
    }

    pub fn add(&mut self, target: NodeId, message: Message) {
        self.messages.entry(target).or_default().push(message);
    }

    pub fn take_for_peer(&mut self, peer_id: &NodeId) -> Vec<Message> {
        self.messages.remove(peer_id).unwrap_or_default()
    }

    pub fn contains_message_for_peer(&self, peer_id: &NodeId, message_id: &MessageId) -> bool {
        self.messages
            .get(peer_id)
            .is_some_and(|messages| messages.iter().any(|message| &message.message_id == message_id))
    }

    pub fn is_empty(&self) -> bool {
        self.messages.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stores_pending_message_for_peer() {
        let mut store = PendingStore::new();

        let target = NodeId("node_c".to_string());
        let message = Message::private_text(
            NodeId("node_a".to_string()),
            target.clone(),
            "Привет".to_string(),
            1710000000,
        );

        let message_id = message.message_id.clone();

        store.add(target.clone(), message);

        assert!(store.contains_message_for_peer(&target, &message_id));
    }

    #[test]
    fn takes_pending_messages_for_peer() {
        let mut store = PendingStore::new();

        let target = NodeId("node_c".to_string());
        let message = Message::private_text(
            NodeId("node_a".to_string()),
            target.clone(),
            "Привет".to_string(),
            1710000000,
        );

        store.add(target.clone(), message);

        let messages = store.take_for_peer(&target);

        assert_eq!(messages.len(), 1);
        assert!(store.take_for_peer(&target).is_empty());
    }

    #[test]
    fn returns_empty_list_for_unknown_peer() {
        let mut store = PendingStore::new();

        let messages = store.take_for_peer(&NodeId("unknown".to_string()));

        assert!(messages.is_empty());
    }
}