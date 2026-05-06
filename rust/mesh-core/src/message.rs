use serde::{Deserialize, Serialize};
use uuid::Uuid;

const PROTOCOL_VERSION: &str = "0.1.0";
const DEFAULT_TTL: u8 = 5;

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct NodeId(pub String);

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct MessageId(pub Uuid);

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ChatType {
    Private,
    Broadcast,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum MessageType {
    Text,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Payload {
    Text { text: String },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Message {
    pub protocol_version: String,
    pub message_id: MessageId,
    pub message_type: MessageType,
    pub chat_type: ChatType,
    pub from: NodeId,
    pub to: Option<NodeId>,
    pub ttl: u8,
    pub timestamp: u64,
    pub payload: Payload,
}

impl Message {
    pub fn private_text(from: NodeId, to: NodeId, text: String, timestamp: u64) -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION.to_string(),
            message_id: MessageId(Uuid::new_v4()),
            message_type: MessageType::Text,
            chat_type: ChatType::Private,
            from,
            to: Some(to),
            ttl: DEFAULT_TTL,
            timestamp,
            payload: Payload::Text { text },
        }
    }

    pub fn broadcast_text(from: NodeId, text: String, timestamp: u64) -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION.to_string(),
            message_id: MessageId(Uuid::new_v4()),
            message_type: MessageType::Text,
            chat_type: ChatType::Broadcast,
            from,
            to: None,
            ttl: DEFAULT_TTL,
            timestamp,
            payload: Payload::Text { text },
        }
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn creates_private_text_message() {
        let from = NodeId("node_a".to_string());
        let to = NodeId("node_b".to_string());

        let message = Message::private_text(
            from.clone(),
            to.clone(),
            "Привет".to_string(),
            1710000000,
        );

        assert_eq!(message.protocol_version, "0.1.0");
        assert_eq!(message.message_type, MessageType::Text);
        assert_eq!(message.chat_type, ChatType::Private);
        assert_eq!(message.from, from);
        assert_eq!(message.to, Some(to));
        assert_eq!(message.ttl, 5);
        assert_eq!(message.timestamp, 1710000000);
        assert_eq!(
            message.payload,
            Payload::Text {
                text: "Привет".to_string()
            }
        );
    }

    #[test]
    fn creates_broadcast_text_message() {
        let from = NodeId("node_a".to_string());

        let message = Message::broadcast_text(
            from.clone(),
            "Всем привет".to_string(),
            1710000000,
        );

        assert_eq!(message.protocol_version, "0.1.0");
        assert_eq!(message.message_type, MessageType::Text);
        assert_eq!(message.chat_type, ChatType::Broadcast);
        assert_eq!(message.from, from);
        assert_eq!(message.to, None);
        assert_eq!(message.ttl, 5);
        assert_eq!(message.timestamp, 1710000000);
        assert_eq!(
            message.payload,
            Payload::Text {
                text: "Всем привет".to_string()
            }
        );
    }

    #[test]
    fn private_messages_have_different_ids() {
        let first = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Первое".to_string(),
            1710000000,
        );

        let second = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Второе".to_string(),
            1710000001,
        );

        assert_ne!(first.message_id, second.message_id);
    }
}