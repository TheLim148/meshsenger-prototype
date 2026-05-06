use std::collections::HashSet;

use crate::message::{ChatType, Message, MessageId, NodeId};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MeshAction {
    ShowMessage(Message),
    ForwardMessage(Message),
    DropMessage,
}

#[derive(Debug)]
pub struct MeshCore {
    node_id: NodeId,
    seen_messages: HashSet<MessageId>,
}

impl MeshCore {
    pub fn new(node_id: NodeId) -> Self {
        Self {
            node_id,
            seen_messages: HashSet::new(),
        }
    }

    pub fn handle_incoming_message(&mut self, message: Message) -> Vec<MeshAction> {
        if self.seen_messages.contains(&message.message_id) {
            return vec![MeshAction::DropMessage];
        }

        self.seen_messages.insert(message.message_id.clone());

        let mut actions = Vec::new();

        if self.should_show_message(&message) {
            actions.push(MeshAction::ShowMessage(message.clone()));
        }

        if self.should_forward_message(&message) {
            let mut forwarded_message = message;
            forwarded_message.ttl -= 1;

            actions.push(MeshAction::ForwardMessage(forwarded_message));
        }

        if actions.is_empty() {
            actions.push(MeshAction::DropMessage);
        }

        actions
    }

    fn should_show_message(&self, message: &Message) -> bool {
        match message.chat_type {
            ChatType::Private => message.to.as_ref() == Some(&self.node_id),
            ChatType::Broadcast => message.from != self.node_id,
        }
    }

    fn should_forward_message(&self, message: &Message) -> bool {
        message.ttl > 0 && message.from != self.node_id
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::Message;

    #[test]
    fn shows_private_message_for_current_node() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(message);

        assert!(matches!(actions[0], MeshAction::ShowMessage(_)));
    }

    #[test]
    fn does_not_show_private_message_for_another_node() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(message);

        assert!(!actions
            .iter()
            .any(|action| matches!(action, MeshAction::ShowMessage(_))));
    }

    #[test]
    fn forwards_message_when_ttl_is_greater_than_zero() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(message);

        assert!(actions
            .iter()
            .any(|action| matches!(action, MeshAction::ForwardMessage(_))));
    }

    #[test]
    fn decreases_ttl_when_forwarding() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let mut message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        message.ttl = 5;

        let actions = core.handle_incoming_message(message);

        let forwarded_message = actions
            .iter()
            .find_map(|action| match action {
                MeshAction::ForwardMessage(message) => Some(message),
                _ => None,
            })
            .unwrap();

        assert_eq!(forwarded_message.ttl, 4);
    }

    #[test]
    fn drops_duplicate_message() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let _ = core.handle_incoming_message(message.clone());
        let actions = core.handle_incoming_message(message);

        assert_eq!(actions, vec![MeshAction::DropMessage]);
    }
}