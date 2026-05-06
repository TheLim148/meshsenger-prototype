use crate::core::{MeshAction, MeshCore};
use crate::message::{ChatType, Message, NodeId, Payload};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiChatMessage {
    pub message_id: String,
    pub chat_type: String,
    pub from: String,
    pub to: Option<String>,
    pub text: String,
    pub timestamp: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ApiAction {
    ShowMessage {
        message: ApiChatMessage,
    },
    ForwardMessage {
        target_peer_ids: Vec<String>,
        bytes: Vec<u8>,
    },
    DropMessage,
    Error {
        message: String,
    },
}

#[derive(Debug)]
pub struct MeshCoreApi {
    core: MeshCore,
}

impl MeshCoreApi {
    pub fn new(node_id: String) -> Self {
        Self {
            core: MeshCore::new(NodeId(node_id)),
        }
    }

    pub fn create_private_text_bytes(
        &self,
        to: String,
        text: String,
        timestamp: u64,
    ) -> Result<Vec<u8>, String> {
        self.core
            .create_private_text_bytes(NodeId(to), text, timestamp)
            .map_err(|error| error.to_string())
    }

    pub fn create_broadcast_text_bytes(
        &self,
        text: String,
        timestamp: u64,
    ) -> Result<Vec<u8>, String> {
        self.core
            .create_broadcast_text_bytes(text, timestamp)
            .map_err(|error| error.to_string())
    }

    pub fn handle_incoming_bytes(
        &mut self,
        from_peer_id: String,
        bytes: Vec<u8>,
    ) -> Vec<ApiAction> {
        self.core
            .handle_incoming_bytes(&NodeId(from_peer_id), &bytes)
            .into_iter()
            .map(api_action_from_mesh_action)
            .collect()
    }

    pub fn mark_peer_connected(&mut self, peer_id: String) {
        self.core.mark_peer_connected(NodeId(peer_id));
    }

    pub fn mark_peer_disconnected(&mut self, peer_id: String) {
        self.core.mark_peer_disconnected(&NodeId(peer_id));
    }

    pub fn take_pending_bytes_for_peer(&mut self, peer_id: String) -> Vec<Vec<u8>> {
        self.core.take_pending_bytes_for_peer(&NodeId(peer_id))
    }

    pub fn connected_peers(&self) -> Vec<String> {
        self.core
            .connected_peers()
            .into_iter()
            .map(|peer_id| peer_id.0)
            .collect()
    }

    pub fn seen_messages_count(&self) -> usize {
        self.core.seen_messages_count()
    }

    pub fn connected_peers_count(&self) -> usize {
        self.core.connected_peers_count()
    }

    pub fn pending_messages_count(&self) -> usize {
        self.core.pending_messages_count()
    }

    pub fn pending_messages_count_for_peer(&self, peer_id: String) -> usize {
        self.core.pending_messages_count_for_peer(&NodeId(peer_id))
    }
}

fn api_action_from_mesh_action(action: MeshAction) -> ApiAction {
    match action {
        MeshAction::ShowMessage(message) => ApiAction::ShowMessage {
            message: api_chat_message_from_message(&message),
        },

        MeshAction::ForwardMessage {
            target_peer_ids,
            bytes,
        } => ApiAction::ForwardMessage {
            target_peer_ids: target_peer_ids
                .into_iter()
                .map(|peer_id| peer_id.0)
                .collect(),
            bytes,
        },

        MeshAction::DropMessage => ApiAction::DropMessage,

        MeshAction::Error(message) => ApiAction::Error { message },
    }
}

fn api_chat_message_from_message(message: &Message) -> ApiChatMessage {
    let chat_type = match message.chat_type {
        ChatType::Private => "private",
        ChatType::Broadcast => "broadcast",
    }
    .to_string();

    let text = match &message.payload {
        Payload::Text { text } => text.clone(),
    };

    ApiChatMessage {
        message_id: message.message_id.0.to_string(),
        chat_type,
        from: message.from.0.clone(),
        to: message.to.as_ref().map(|node_id| node_id.0.clone()),
        text,
        timestamp: message.timestamp,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::codec::decode_message;
    use crate::message::{ChatType, Message, NodeId, Payload};

    #[test]
    fn creates_private_text_bytes_via_api() {
        let api = MeshCoreApi::new("node_a".to_string());

        let bytes = api
            .create_private_text_bytes("node_b".to_string(), "Привет".to_string(), 1710000000)
            .unwrap();

        let message = decode_message(&bytes).unwrap();

        assert_eq!(message.from, NodeId("node_a".to_string()));
        assert_eq!(message.to, Some(NodeId("node_b".to_string())));
        assert_eq!(message.chat_type, ChatType::Private);
        assert_eq!(
            message.payload,
            Payload::Text {
                text: "Привет".to_string(),
            }
        );
    }

    #[test]
    fn creates_broadcast_text_bytes_via_api() {
        let api = MeshCoreApi::new("node_a".to_string());

        let bytes = api
            .create_broadcast_text_bytes("Всем привет".to_string(), 1710000000)
            .unwrap();

        let message = decode_message(&bytes).unwrap();

        assert_eq!(message.from, NodeId("node_a".to_string()));
        assert_eq!(message.to, None);
        assert_eq!(message.chat_type, ChatType::Broadcast);
    }

    #[test]
    fn handles_incoming_private_message_via_api() {
        let sender_api = MeshCoreApi::new("node_a".to_string());
        let mut receiver_api = MeshCoreApi::new("node_b".to_string());

        let bytes = sender_api
            .create_private_text_bytes("node_b".to_string(), "Привет".to_string(), 1710000000)
            .unwrap();

        let actions = receiver_api.handle_incoming_bytes("node_a".to_string(), bytes);

        let shown_message = actions
            .iter()
            .find_map(|action| match action {
                ApiAction::ShowMessage { message } => Some(message),
                _ => None,
            })
            .unwrap();

        assert_eq!(shown_message.from, "node_a");
        assert_eq!(shown_message.to, Some("node_b".to_string()));
        assert_eq!(shown_message.text, "Привет");
        assert_eq!(shown_message.chat_type, "private");
    }

    #[test]
    fn returns_error_for_invalid_bytes_via_api() {
        let mut api = MeshCoreApi::new("node_b".to_string());

        let actions = api.handle_incoming_bytes("node_a".to_string(), b"invalid bytes".to_vec());

        assert!(
            actions
                .iter()
                .any(|action| matches!(action, ApiAction::Error { .. }))
        );
    }

    #[test]
    fn forwards_private_message_to_connected_peer_via_api() {
        let mut api = MeshCoreApi::new("node_b".to_string());

        api.mark_peer_connected("node_c".to_string());

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let bytes = crate::codec::encode_message(&message).unwrap();

        let actions = api.handle_incoming_bytes("node_a".to_string(), bytes);

        let target_peer_ids = actions
            .iter()
            .find_map(|action| match action {
                ApiAction::ForwardMessage {
                    target_peer_ids, ..
                } => Some(target_peer_ids),
                _ => None,
            })
            .unwrap();

        assert_eq!(target_peer_ids, &vec!["node_c".to_string()]);
    }

    #[test]
    fn stores_pending_message_via_api() {
        let mut api = MeshCoreApi::new("node_b".to_string());

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let bytes = crate::codec::encode_message(&message).unwrap();

        api.handle_incoming_bytes("node_a".to_string(), bytes);

        assert_eq!(api.pending_messages_count(), 1);
        assert_eq!(api.pending_messages_count_for_peer("node_c".to_string()), 1);
    }

    #[test]
    fn takes_pending_bytes_for_peer_via_api() {
        let mut api = MeshCoreApi::new("node_b".to_string());

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let message_id = message.message_id.clone();

        let bytes = crate::codec::encode_message(&message).unwrap();

        api.handle_incoming_bytes("node_a".to_string(), bytes);

        let pending_bytes = api.take_pending_bytes_for_peer("node_c".to_string());

        assert_eq!(pending_bytes.len(), 1);

        let pending_message = decode_message(&pending_bytes[0]).unwrap();

        assert_eq!(pending_message.message_id, message_id);
        assert_eq!(api.pending_messages_count(), 0);
    }

    #[test]
    fn tracks_connected_peers_via_api() {
        let mut api = MeshCoreApi::new("node_a".to_string());

        api.mark_peer_connected("node_b".to_string());
        api.mark_peer_connected("node_c".to_string());

        let peers = api.connected_peers();

        assert_eq!(api.connected_peers_count(), 2);
        assert!(peers.contains(&"node_b".to_string()));
        assert!(peers.contains(&"node_c".to_string()));
    }

    #[test]
    fn disconnects_peer_via_api() {
        let mut api = MeshCoreApi::new("node_a".to_string());

        api.mark_peer_connected("node_b".to_string());
        assert_eq!(api.connected_peers_count(), 1);

        api.mark_peer_disconnected("node_b".to_string());
        assert_eq!(api.connected_peers_count(), 0);
    }
}
