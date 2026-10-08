package com.gamma.alert;

/**
 * The stored type text of the Alerts that were once operational <em>objects</em> (before MODULE-REORG-P7-INCIDENTS
 * slice 2 moved them into the Alert-owned {@link AlertStore}). No enum names it any more: a deployed Space's object
 * table may still hold rows of this type, which load inert, and an {@code ESCALATED_FROM} link names an Alert as this
 * kind. The one-shot {@link AlertMigration} and the link writer use this constant.
 */
public final class LegacyAlertObjects {

    /** The stored {@code object_type} text of a legacy ALERT object, and the link kind an Incident escalates from. */
    public static final String TYPE = "ALERT";

    private LegacyAlertObjects() {}
}
