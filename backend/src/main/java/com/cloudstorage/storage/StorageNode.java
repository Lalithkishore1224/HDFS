package com.cloudstorage.storage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One storage node as the cluster sees it. It is in use only when it is both reachable (it answers
 * its heartbeat) and not switched to maintenance by the administrator.
 */
public final class StorageNode {

    /** Why a node is or is not serving. */
    public enum State { ONLINE, MAINTENANCE, UNREACHABLE }

    private final String name;
    private final NodeStore store;
    private final String endpoint;
    private final AtomicBoolean maintenance = new AtomicBoolean(false);
    private final AtomicBoolean reachable = new AtomicBoolean(true);

    StorageNode(String name, NodeStore store, String endpoint) {
        this.name = name;
        this.store = store;
        this.endpoint = endpoint;
    }

    public String name() {
        return name;
    }

    public NodeStore store() {
        return store;
    }

    /** The node's HTTP address, or null for a node that is a directory on this machine. */
    public String endpoint() {
        return endpoint;
    }

    public boolean isOnline() {
        return reachable.get() && !maintenance.get();
    }

    public State state() {
        if (!reachable.get()) {
            return State.UNREACHABLE;
        }
        return maintenance.get() ? State.MAINTENANCE : State.ONLINE;
    }

    void setOnline(boolean value) {
        maintenance.set(!value);
    }

    boolean isReachable() {
        return reachable.get();
    }

    void setReachable(boolean value) {
        reachable.set(value);
    }
}
