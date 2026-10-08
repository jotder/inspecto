package com.gamma.ops;

import java.util.function.UnaryOperator;

import com.gamma.ops.link.LinkStore;
import com.gamma.ops.note.NoteStore;
import com.gamma.ops.tag.TagAssignmentStore;

/**
 * The generic object substrate {@link ObjectService} hands to a collaborator that implements one object
 * type's behaviour (MODULE-REORG-P7: Case operations): the three stores, the optimistic read-modify-write,
 * and the audit event source. Everything else the collaborator needs is already public on
 * {@code ObjectService}.
 */
public interface ObjectSubstrate {
    ObjectStore store();
    LinkStore links();
    NoteStore notes();
    TagAssignmentStore tagAssignments();
    /** The object, or {@link java.util.NoSuchElementException}. */
    OperationalObject require(String id);
    /** Read fresh, apply {@code change} (pure), persist, retrying a lost optimistic-lock race. */
    OperationalObject rmw(String id, UnaryOperator<OperationalObject> change);
    /** Compensation for a failed multi-object write: remove one object this call created, cascading its notes, links and tag edges first. */
    void discard(String objectId, String actor, RuntimeException cause);
    /** The {@code source} stamped on every audit event the service emits. */
    String eventSource();
}
