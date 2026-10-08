package com.gamma.service;

/**
 * One operational-store <b>family</b>: how a store is switched on, addressed and credentialed. The roster is open
 * (MODULE-REORG-P1-FAMILY, plan 8c item 1): the families every edition carries are the {@link OperationalDb.Family}
 * constants, and an optional module contributes its own through a {@link StoreFamilyProvider} - {@code inspecto-ops}
 * owns the four Operational Object families. ⚠ The platform still owns HOW a family is addressed and selected
 * ({@link OperationalDb#resolve}, {@link OperationalDb#urlFor}, {@link OperationalDb#userFor},
 * {@link OperationalDb#passwordFor}, the Postgres schema-per-Space scoping); a family only DECLARES its properties
 * and its per-Space DuckDB default, so no module can re-implement the precedence and drift from it.
 *
 * <p>Implementations are value-like and stateless; {@link #name()} is the family's id on the wire
 * ({@code /system/operational-db}) and in {@code module.toon}'s {@code provides.storeFamilies}.
 */
public interface StoreFamily {

    /** How a family spells "enabled" on its {@code *.backend} property - they genuinely differ. */
    enum Mode {
        /** {@code duckdb} | {@code postgres} | a raw {@code jdbc:...} URL (which then IS the URL). */
        URL_OR_ENGINE,
        /** {@code db}, against a non-DB default ({@code memory} / {@code file}). */
        DB_FLAG
    }

    /** The family's stable id (an enum constant's name for the core families). */
    String name();

    String label();

    String backendProperty();

    String backendDefault();

    Mode mode();

    String urlProperty();

    /** May be {@code null} - several families open with a URL and no credentials at all. */
    String userProperty();

    /** May be {@code null}, as {@link #userProperty()}. */
    String passwordProperty();

    /** The family's own embedded DuckDB URL for a Space (the {@code SpaceRoot} accessor it has always used). */
    String spaceDefault(SpaceRoot root);
}
