package com.titiplex.budget.core.crdt;

public class HLC implements Comparable<HLC> {
    public final long wall;   // ms
    public final int logic;   // counter
    public final String node; // node id

    public HLC(long wall, int logic, String node) {
        this.wall = wall;
        this.logic = logic;
        this.node = node;
    }

    public static HLC parse(String s) {
        String[] p = s.split(":");
        return new HLC(Long.parseLong(p[0]), Integer.parseInt(p[1]), p[2]);
    }

    @Override
    public int compareTo(HLC o) {
        if (wall != o.wall) return Long.compare(wall, o.wall);
        if (logic != o.logic) return Integer.compare(logic, o.logic);
        return node.compareTo(o.node);
    }

    @Override
    public String toString() {
        return wall + ":" + logic + ":" + node;
    }

    public static class Clock {
        private long lastWall = 0;
        private int lastLogic = 0;
        private final String nodeId;

        public Clock(String nodeId) {
            this.nodeId = nodeId;
        }

        public synchronized String tick() {
            long now = System.currentTimeMillis();
            if (now > lastWall) {
                lastWall = now;
                lastLogic = 0;
            } else {
                lastLogic += 1;
            }
            return new HLC(lastWall, lastLogic, nodeId).toString();
        }

        public synchronized String merge(String remote) {
            HLC r = HLC.parse(remote);
            long now = System.currentTimeMillis();
            long wall = Math.max(Math.max(now, lastWall), r.wall);
            int logic;
            if (wall == lastWall && wall == r.wall) logic = Math.max(lastLogic, r.logic) + 1;
            else if (wall == lastWall) logic = lastLogic + 1;
            else if (wall == r.wall) logic = r.logic + 1;
            else logic = 0;
            lastWall = wall;
            lastLogic = logic;
            return new HLC(wall, logic, nodeId).toString();
        }
    }
}