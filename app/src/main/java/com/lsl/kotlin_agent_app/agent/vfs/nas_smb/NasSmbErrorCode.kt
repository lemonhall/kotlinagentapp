package com.lsl.kotlin_agent_app.agent.vfs.nas_smb

enum class NasSmbErrorCode {
    Timeout,
    AuthFailed,
    ShareNotFound,
    PermissionDenied,
    HostUnreachable,

    InvalidConfig,
    MissingCredentials,
}

