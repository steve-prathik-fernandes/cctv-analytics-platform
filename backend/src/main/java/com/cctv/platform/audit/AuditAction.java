package com.cctv.platform.audit;

/** Actions written to the audit log. Stored by name, so do not rename existing values. */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGIN_BLOCKED,
    USER_CREATE,
    USER_UPDATE,
    USER_CAMERAS_UPDATE,
    CAMERA_CREATE
}
