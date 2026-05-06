use mesh_core::{decode_message, ApiAction, MeshCoreApi};

#[test]
fn delivers_private_message_from_a_to_c_through_b() {
    let node_a = MeshCoreApi::new("node_a".to_string());
    let mut node_b = MeshCoreApi::new("node_b".to_string());
    let mut node_c = MeshCoreApi::new("node_c".to_string());

    node_b.mark_peer_connected("node_a".to_string());
    node_b.mark_peer_connected("node_c".to_string());

    node_c.mark_peer_connected("node_b".to_string());

    let bytes_from_a = node_a
        .create_private_text_bytes(
            "node_c".to_string(),
            "Привет через B".to_string(),
            1710000000,
        )
        .unwrap();

    let b_actions = node_b.handle_incoming_bytes(
        "node_a".to_string(),
        bytes_from_a,
    );

    assert!(!b_actions
        .iter()
        .any(|action| matches!(action, ApiAction::ShowMessage { .. })));

    assert_eq!(node_b.pending_messages_count(), 0);

    let (target_peer_ids, forwarded_bytes) = b_actions
        .iter()
        .find_map(|action| match action {
            ApiAction::ForwardMessage {
                target_peer_ids,
                bytes,
            } => Some((target_peer_ids.clone(), bytes.clone())),
            _ => None,
        })
        .unwrap();

    assert_eq!(target_peer_ids, vec!["node_c".to_string()]);
    assert!(!target_peer_ids.contains(&"node_a".to_string()));

    let forwarded_message = decode_message(&forwarded_bytes).unwrap();

    assert_eq!(forwarded_message.ttl, 4);

    let c_actions = node_c.handle_incoming_bytes(
        "node_b".to_string(),
        forwarded_bytes,
    );

    let shown_message = c_actions
        .iter()
        .find_map(|action| match action {
            ApiAction::ShowMessage { message } => Some(message),
            _ => None,
        })
        .unwrap();

    assert_eq!(shown_message.from, "node_a");
    assert_eq!(shown_message.to, Some("node_c".to_string()));
    assert_eq!(shown_message.text, "Привет через B");
    assert_eq!(shown_message.chat_type, "private");

    assert_eq!(node_b.seen_messages_count(), 1);
    assert_eq!(node_c.seen_messages_count(), 1);
}

#[test]
fn stores_message_on_b_and_delivers_later_when_c_becomes_available() {
    let node_a = MeshCoreApi::new("node_a".to_string());
    let mut node_b = MeshCoreApi::new("node_b".to_string());
    let mut node_c = MeshCoreApi::new("node_c".to_string());

    node_b.mark_peer_connected("node_a".to_string());

    let bytes_from_a = node_a
        .create_private_text_bytes(
            "node_c".to_string(),
            "Сообщение с задержкой".to_string(),
            1710000000,
        )
        .unwrap();

    let b_actions = node_b.handle_incoming_bytes(
        "node_a".to_string(),
        bytes_from_a,
    );

    assert!(!b_actions
        .iter()
        .any(|action| matches!(action, ApiAction::ForwardMessage { .. })));

    assert_eq!(node_b.pending_messages_count(), 1);
    assert_eq!(
        node_b.pending_messages_count_for_peer("node_c".to_string()),
        1
    );

    node_b.mark_peer_connected("node_c".to_string());
    node_c.mark_peer_connected("node_b".to_string());

    let pending_bytes = node_b.take_pending_bytes_for_peer(
        "node_c".to_string(),
    );

    assert_eq!(pending_bytes.len(), 1);
    assert_eq!(node_b.pending_messages_count(), 0);

    let c_actions = node_c.handle_incoming_bytes(
        "node_b".to_string(),
        pending_bytes[0].clone(),
    );

    let shown_message = c_actions
        .iter()
        .find_map(|action| match action {
            ApiAction::ShowMessage { message } => Some(message),
            _ => None,
        })
        .unwrap();

    assert_eq!(shown_message.from, "node_a");
    assert_eq!(shown_message.to, Some("node_c".to_string()));
    assert_eq!(shown_message.text, "Сообщение с задержкой");
}