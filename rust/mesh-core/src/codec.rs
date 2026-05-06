use crate::message::Message;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CodecError {
    EncodeFailed(String),
    DecodeFailed(String),
}

pub fn encode_message(message: &Message) -> Result<Vec<u8>, CodecError> {
    serde_json::to_vec(message)
        .map_err(|error| CodecError::EncodeFailed(error.to_string()))
}

pub fn decode_message(bytes: &[u8]) -> Result<Message, CodecError> {
    serde_json::from_slice(bytes)
        .map_err(|error| CodecError::DecodeFailed(error.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::message::{Message, NodeId, Payload};

    #[test]
    fn encodes_and_decodes_private_text_message() {
        let message = Message::private_text(
            NodeId("node_a".to_string()),
            NodeId("node_b".to_string()),
            "Привет".to_string(),
            1710000000,
        );

        let bytes = encode_message(&message).unwrap();
        let decoded_message = decode_message(&bytes).unwrap();

        assert_eq!(decoded_message, message);
    }

    #[test]
    fn decodes_text_payload_correctly() {
        let message = Message::broadcast_text(
            NodeId("node_a".to_string()),
            "Всем привет".to_string(),
            1710000000,
        );

        let bytes = encode_message(&message).unwrap();
        let decoded_message = decode_message(&bytes).unwrap();

        assert_eq!(
            decoded_message.payload,
            Payload::Text {
                text: "Всем привет".to_string()
            }
        );
    }

    #[test]
    fn returns_error_for_invalid_json() {
        let bytes = b"{ invalid json }";

        let result = decode_message(bytes);

        assert!(matches!(result, Err(CodecError::DecodeFailed(_))));
    }

    #[test]
    fn returns_error_for_empty_bytes() {
        let bytes = b"";

        let result = decode_message(bytes);

        assert!(matches!(result, Err(CodecError::DecodeFailed(_))));
    }
}