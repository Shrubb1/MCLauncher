package com.mclauncher.client;

import java.util.ArrayDeque;
import java.util.Deque;

/** Live (non-persisted) HUD state: CPS click rings, toggle flags, zoom. */
public class HudState {

    private final Deque<Long> leftClicks = new ArrayDeque<>();
    private final Deque<Long> rightClicks = new ArrayDeque<>();

    public boolean zooming;
    public boolean sprintToggled;
    public boolean sneakToggled;
    /** A bot task (pro navigator) is running - Right Shift stops it
     * instead of opening the config screen */
    public boolean navActive;

    public void click(int button) {
        long now = System.currentTimeMillis();
        (button == 0 ? leftClicks : rightClicks).addLast(now);
        trim(leftClicks, now);
        trim(rightClicks, now);
    }

    private static void trim(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 1000) {
            q.pollFirst();
        }
    }

    public int cps(int button) {
        return (button == 0 ? leftClicks : rightClicks).size();
    }
}
