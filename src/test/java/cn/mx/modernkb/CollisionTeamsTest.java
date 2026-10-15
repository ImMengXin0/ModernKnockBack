package cn.mx.modernkb;

import cn.mx.modernkb.compat.CollisionTeams;
import cn.mx.modernkb.compat.CollisionTeams.Packet;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollisionTeamsTest {
    private record Style(String prefix, String suffix, String color, boolean invisible, String collision) {}
    private final Style original = new Style("[VIP]", "!", "red", false, "pushOtherTeams");
    private final Style noPush = new Style("[VIP]", "!", "red", false, "never");
    private CollisionTeams<Style> state() {
        return new CollisionTeams<>("Alice", "mkb_test", new Style("", "", "white", true, "never"),
                s -> new Style(s.prefix, s.suffix, s.color, s.invisible, "never"));
    }
    @Test public void enablingAndDisablingPreservesTeamMembershipAndLatestFormatting() {
        var state = state();
        var team = new Packet<>("staff", 0, original, List.of("Alice", "Bob"));
        assertEquals(List.of(team), state.accept(team));
        var enabled = state.enabled(true);
        assertEquals(new Packet<>("staff", 2, noPush, List.of()), enabled.getFirst());
        assertTrue(enabled.getLast().players().isEmpty());
        var changed = new Style("[Owner]", ":", "gold", true, "always");
        assertEquals("never", state.accept(new Packet<>("staff", 2, changed, List.of())).getFirst().parameters().collision());
        var disabled = state.enabled(false);
        assertEquals(1, disabled.getFirst().method());
        assertEquals(new Packet<>("staff", 2, changed, List.of()), disabled.getLast());
        assertEquals("pushOtherTeams", original.collision());
    }
    @Test public void unteamedViewerGetsOnlyClientFallbackMembership() {
        var state = state();
        var enabled = state.enabled(true);
        assertEquals(1, enabled.size());
        assertEquals(List.of("Alice"), enabled.getFirst().players());
        assertTrue(state.enabled(true).isEmpty());
        assertEquals(List.of(new Packet<Style>("mkb_test", 1, null, List.of())), state.enabled(false));
    }
    @Test public void removingOwnTeamImmediatelyRestoresFallbackWithoutChangingOthers() {
        var state = state();
        state.accept(new Packet<>("staff", 0, original, List.of("Alice", "Bob")));
        state.enabled(true);
        var output = state.accept(new Packet<>("staff", 1, null, List.of()));
        assertEquals(2, output.size());
        assertEquals(new Packet<>("mkb_test", 3, null, List.of("Alice")), output.getLast());
        assertEquals(1, state.enabled(false).size()); // removed teams are not resurrected
    }
    @Test public void movingBetweenTeamsDoesNotStealMembershipOrRestoreDeletedTeams() {
        var state = state();
        state.enabled(true);
        state.accept(new Packet<>("red", 0, original, List.of("Alice")));
        state.accept(new Packet<>("blue", 0, original, List.of("Bob")));
        assertEquals(1, state.accept(new Packet<>("blue", 3, null, List.of("Alice"))).size());
        assertEquals(1, state.accept(new Packet<>("red", 1, null, List.of())).size());
        var leave = state.accept(new Packet<>("blue", 4, null, List.of("Alice")));
        assertEquals(List.of("Alice"), leave.getLast().players());
        assertEquals("mkb_test", leave.getLast().name());
        assertEquals("blue", state.enabled(false).getLast().name());
    }
    @Test public void modernPassesPacketsUnchangedAndNextClassicUsesNewState() {
        var state = state();
        var team = new Packet<>("staff", 0, original, List.of("Alice"));
        assertSame(team, state.accept(team).getFirst());
        state.enabled(true); state.enabled(false);
        var remove = new Packet<Style>("staff", 1, null, List.of());
        assertEquals(List.of(remove), state.accept(remove));
        assertEquals(List.of("Alice"), state.enabled(true).getFirst().players());
    }
}
