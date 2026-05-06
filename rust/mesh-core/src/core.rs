use std::collections::HashSet;

use crate::codec::{CodecError, decode_message, encode_message};
use crate::message::{ChatType, Message, MessageId, NodeId};
use crate::store::PendingStore;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MeshAction {
    ShowMessage(Message),
    ForwardMessage {
        target_peer_ids: Vec<NodeId>,
        bytes: Vec<u8>,
    },
    DropMessage,
    Error(String),
}

#[derive(Debug)]
pub struct MeshCore {
    node_id: NodeId,
    seen_messages: HashSet<MessageId>,
    pending_store: PendingStore,
    connected_peers: HashSet<NodeId>,
}

impl MeshCore {
    pub fn new(node_id: NodeId) -> Self {
        Self {
            node_id,
            seen_messages: HashSet::new(),
            pending_store: PendingStore::new(),
            connected_peers: HashSet::new(),
        }
    }

    pub fn mark_peer_connected(&mut self, peer_id: NodeId) {
        self.connected_peers.insert(peer_id);
    }

    pub fn mark_peer_disconnected(&mut self, peer_id: &NodeId) {
        self.connected_peers.remove(peer_id);
    }

    pub fn connected_peers(&self) -> Vec<NodeId> {
        self.connected_peers.iter().cloned().collect()
    }

    pub fn take_pending_bytes_for_peer(&mut self, peer_id: &NodeId) -> Vec<Vec<u8>> {
        self.pending_store
            .take_for_peer(peer_id)
            .into_iter()
            .filter_map(|message| encode_message(&message).ok())
            .collect()
    }

    pub fn handle_incoming_message(
        &mut self,
        from_peer: &NodeId,
        message: Message,
    ) -> Vec<MeshAction> {
        if self.seen_messages.contains(&message.message_id) {
            return vec![MeshAction::DropMessage];
        }

        self.seen_messages.insert(message.message_id.clone());

        let mut actions = Vec::new();

        if self.should_show_message(&message) {
            actions.push(MeshAction::ShowMessage(message.clone()));
        }

        if self.should_store_message(&message) {
            if let Some(target) = message.to.clone() {
                self.pending_store.add(target, message.clone());
            }
        }

        if self.should_forward_message(&message) {
            let mut forwarded_message = message;
            forwarded_message.ttl -= 1;

            let target_peer_ids = self.forward_targets(from_peer, &forwarded_message);

            if !target_peer_ids.is_empty() {
                match encode_message(&forwarded_message) {
                    Ok(bytes) => actions.push(MeshAction::ForwardMessage {
                        target_peer_ids,
                        bytes,
                    }),
                    Err(error) => actions.push(MeshAction::Error(error.to_string())),
                }
            }
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

    fn should_store_message(&self, message: &Message) -> bool {
        message.chat_type == ChatType::Private
            && message.to.as_ref() != Some(&self.node_id)
            && message.from != self.node_id
    }

    pub fn handle_incoming_bytes(&mut self, from_peer: &NodeId, bytes: &[u8]) -> Vec<MeshAction> {
        match decode_message(bytes) {
            Ok(message) => self.handle_incoming_message(from_peer, message),
            Err(error) => vec![MeshAction::Error(error.to_string())],
        }
    }

    pub fn create_private_text_bytes(
        &self,
        to: NodeId,
        text: String,
        timestamp: u64,
    ) -> Result<Vec<u8>, CodecError> {
        let message = Message::private_text(self.node_id.clone(), to, text, timestamp);

        encode_message(&message)
    }

    pub fn create_broadcast_text_bytes(
        &self,
        text: String,
        timestamp: u64,
    ) -> Result<Vec<u8>, CodecError> {
        let message = Message::broadcast_text(self.node_id.clone(), text, timestamp);

        encode_message(&message)
    }

    fn forward_targets(&self, from_peer: &NodeId, message: &Message) -> Vec<NodeId> {
        match message.chat_type {
            ChatType::Private => message
                .to
                .clone()
                .filter(|target| self.connected_peers.contains(target) && target != from_peer)
                .into_iter()
                .collect(),

            ChatType::Broadcast => self
                .connected_peers()
                .into_iter()
                .filter(|peer| peer != from_peer)
                .collect(),
        }
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

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

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

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        assert!(
            !actions
                .iter()
                .any(|action| matches!(action, MeshAction::ShowMessage(_)))
        );
    }

    #[test]
    fn forwards_message_when_ttl_is_greater_than_zero() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let target = NodeId("node_c".to_string());
        core.mark_peer_connected(target.clone());

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            target,
            "Привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        assert!(
            actions
                .iter()
                .any(|action| matches!(action, MeshAction::ForwardMessage { .. }))
        );
    }

    #[test]
    fn decreases_ttl_when_forwarding() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let target = NodeId("node_c".to_string());
        core.mark_peer_connected(target.clone());

        let mut message = Message::private_text(
            NodeId("node_a".to_string()),
            target,
            "Привет".to_string(),
            1710000000,
        );

        message.ttl = 5;

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        let forwarded_bytes = actions
            .iter()
            .find_map(|action| match action {
                MeshAction::ForwardMessage { bytes, .. } => Some(bytes),
                _ => None,
            })
            .unwrap();

        let forwarded_message = decode_message(forwarded_bytes).unwrap();

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

        let _ = core.handle_incoming_message(&NodeId("node_a".to_string()), message.clone());
        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        assert_eq!(actions, vec![MeshAction::DropMessage]);
    }

    #[test]
    fn handles_incoming_bytes() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let bytes = encode_message(&message).unwrap();
        let actions = core.handle_incoming_bytes(&NodeId("node_a".to_string()), &bytes);

        assert!(
            actions
                .iter()
                .any(|action| matches!(action, MeshAction::ShowMessage(_)))
        );
    }

    #[test]
    fn returns_error_for_invalid_incoming_bytes() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let actions = core.handle_incoming_bytes(&NodeId("node_a".to_string()), b"invalid bytes");

        assert!(
            actions
                .iter()
                .any(|action| matches!(action, MeshAction::Error(_)))
        );
    }
    #[test]
    fn stores_private_message_for_unavailable_target() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let target = NodeId("node_c".to_string());

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            target.clone(),
            "Привет".to_string(),
            1710000000,
        );

        let message_id = message.message_id.clone();

        core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        let pending_bytes = core.take_pending_bytes_for_peer(&target);

        assert_eq!(pending_bytes.len(), 1);

        let pending_message = decode_message(&pending_bytes[0]).unwrap();

        assert_eq!(pending_message.message_id, message_id);
    }

    #[test]
    fn does_not_store_message_for_current_node() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        let pending_bytes = core.take_pending_bytes_for_peer(&NodeId("node_b".to_string()));

        assert!(pending_bytes.is_empty());
    }

    #[test]
    fn creates_private_text_bytes_from_current_node() {
        let core = MeshCore::new(NodeId("node_a".to_string()));

        let bytes = core
            .create_private_text_bytes(
                NodeId("node_b".to_string()),
                "Привет".to_string(),
                1710000000,
            )
            .unwrap();

        let message = decode_message(&bytes).unwrap();

        assert_eq!(message.from, NodeId("node_a".to_string()));
        assert_eq!(message.to, Some(NodeId("node_b".to_string())));
        assert_eq!(message.chat_type, ChatType::Private);
    }

    #[test]
    fn creates_broadcast_text_bytes_from_current_node() {
        let core = MeshCore::new(NodeId("node_a".to_string()));

        let bytes = core
            .create_broadcast_text_bytes("Всем привет".to_string(), 1710000000)
            .unwrap();

        let message = decode_message(&bytes).unwrap();

        assert_eq!(message.from, NodeId("node_a".to_string()));
        assert_eq!(message.to, None);
        assert_eq!(message.chat_type, ChatType::Broadcast);
    }

    #[test]
    fn forwards_private_message_to_target_peer() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let target = NodeId("node_c".to_string());

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            target.clone(),
            "Привет".to_string(),
            1710000000,
        );

        core.mark_peer_connected(target.clone());

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        let target_peer_ids = actions
            .iter()
            .find_map(|action| match action {
                MeshAction::ForwardMessage {
                    target_peer_ids, ..
                } => Some(target_peer_ids),
                _ => None,
            })
            .unwrap();

        assert_eq!(target_peer_ids, &vec![target]);
    }

    #[test]
    fn marks_peer_as_connected() {
        let mut core = MeshCore::new(NodeId("node_a".to_string()));

        let peer = NodeId("node_b".to_string());

        core.mark_peer_connected(peer.clone());

        assert!(core.connected_peers().contains(&peer));
    }

    #[test]
    fn marks_peer_as_disconnected() {
        let mut core = MeshCore::new(NodeId("node_a".to_string()));

        let peer = NodeId("node_b".to_string());

        core.mark_peer_connected(peer.clone());
        core.mark_peer_disconnected(&peer);

        assert!(!core.connected_peers().contains(&peer));
    }

    #[test]
    fn does_not_forward_private_message_to_disconnected_target() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_c".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        assert!(
            !actions
                .iter()
                .any(|action| matches!(action, MeshAction::ForwardMessage { .. }))
        );
    }

    #[test]
    fn forwards_broadcast_message_to_connected_peers() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let peer_c = NodeId("node_c".to_string());
        let peer_d = NodeId("node_d".to_string());

        core.mark_peer_connected(peer_c.clone());
        core.mark_peer_connected(peer_d.clone());

        let message = Message::broadcast_text(
            NodeId("node_a".to_string()),
            "Всем привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(&NodeId("node_a".to_string()), message);

        let target_peer_ids = actions
            .iter()
            .find_map(|action| match action {
                MeshAction::ForwardMessage {
                    target_peer_ids, ..
                } => Some(target_peer_ids),
                _ => None,
            })
            .unwrap();

        assert!(target_peer_ids.contains(&peer_c));
        assert!(target_peer_ids.contains(&peer_d));
    }

    #[test]
    fn does_not_forward_message_back_to_sender_peer() {
        let mut core = MeshCore::new(NodeId("node_b".to_string()));

        let peer_a = NodeId("peer_a".to_string());
        let peer_c = NodeId("peer_c".to_string());

        core.mark_peer_connected(peer_a.clone());
        core.mark_peer_connected(peer_c.clone());

        let message = Message::broadcast_text(
            NodeId("node_a".to_string()),
            "Всем привет".to_string(),
            1710000000,
        );

        let actions = core.handle_incoming_message(&peer_a, message);

        let target_peer_ids = actions
            .iter()
            .find_map(|action| match action {
                MeshAction::ForwardMessage {
                    target_peer_ids, ..
                } => Some(target_peer_ids),
                _ => None,
            })
            .unwrap();

        assert!(!target_peer_ids.contains(&peer_a));
        assert!(target_peer_ids.contains(&peer_c));
    }
}
