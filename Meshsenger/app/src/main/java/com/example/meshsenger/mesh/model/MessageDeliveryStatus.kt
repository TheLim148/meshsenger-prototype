package com.example.meshsenger.mesh.model

enum class MessageDeliveryStatus {
    Pending,
    Relayed,
    Sent,
    Delivered,
    Read,
    Failed,

    /** Старое имя оставлено для совместимости с уже сохранёнными историями. */
    Received,
}
