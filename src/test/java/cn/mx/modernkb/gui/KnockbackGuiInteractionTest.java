package cn.mx.modernkb.gui;

import cn.mx.modernkb.ModernKnockbackPlugin;
import cn.mx.modernkb.api.CombatMode;
import cn.mx.modernkb.config.ModeFiles;
import cn.mx.modernkb.config.Settings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercises menu navigation and editing without starting a Minecraft server. */
public class KnockbackGuiInteractionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> items;
    private ModeFiles files;
    private Settings settings;
    private Player player;
    private InventoryView view;
    private Inventory top;
    private KnockbackGui gui;
    private final List<KnockbackGui.Edit> edits = new ArrayList<>();

    @Before public void setup() throws Exception {
        files = new ModeFiles(temporary.getRoot().toPath());
        files.initialize();
        settings = files.load(CombatMode.CLASSIC);
        bukkit = mockStatic(Bukkit.class);
        items = mockConstruction(ItemStack.class, (stack, context) ->
                when(stack.getItemMeta()).thenReturn(mock(ItemMeta.class)));
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
                .thenAnswer(call -> {
                    Inventory inventory = mock(Inventory.class);
                    when(inventory.getHolder()).thenReturn(call.getArgument(0));
                    when(inventory.getSize()).thenReturn(call.getArgument(1));
                    var contents = new HashMap<Integer, ItemStack>();
                    doAnswer(set -> { contents.put(set.getArgument(0), set.getArgument(1)); return null; })
                            .when(inventory).setItem(anyInt(), any());
                    when(inventory.getItem(anyInt())).thenAnswer(get -> contents.get(get.getArgument(0)));
                    return inventory;
                });
        player = mock(Player.class);
        when(player.hasPermission("modernkb.admin")).thenReturn(true);
        view = mock(InventoryView.class);
        when(view.getTopInventory()).thenAnswer(call -> top);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.openInventory(any(Inventory.class))).thenAnswer(call -> { top = call.getArgument(0); return view; });
        gui = new KnockbackGui(mock(ModernKnockbackPlugin.class), () -> settings, (target, edit) -> edits.add(edit));
    }

    @After public void cleanup() {
        if (items != null) items.close();
        if (bukkit != null) bukkit.close();
    }

    @Test public void classicAndModernClicksKeepTheirOwnPathsAndModes() {
        for (CombatMode mode : CombatMode.values()) {
            settings = files.load(mode);
            openPlayerCore();
            click(5, ClickType.LEFT);
            var edit = edits.getLast();
            assertEquals(mode, edit.mode());
            assertEquals("player", edit.profile());
            assertEquals(mode == CombatMode.CLASSIC ? "classic.horizontal-ground" : "horizontal.ground", edit.path());
            assertEquals(mode == CombatMode.CLASSIC ? .45 : 1.05, (Double) edit.value(), 1e-9);
        }
    }

    @Test public void rightShiftNumberAndDoubleClicksCannotChangeParameters() {
        openPlayerCore();
        for (ClickType click : List.of(ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK))
            verify(click(5, click)).setCancelled(true);
        assertTrue(edits.isEmpty());
    }

    @Test public void revokedPermissionClosesExistingMenuWithoutEditing() {
        openPlayerCore();
        when(player.hasPermission("modernkb.admin")).thenReturn(false);
        verify(click(5, ClickType.LEFT)).setCancelled(true);
        assertTrue(edits.isEmpty());
        verify(player).closeInventory();
    }

    @Test public void missingPermissionCannotOpenMenu() {
        when(player.hasPermission("modernkb.admin")).thenReturn(false);
        gui.open(player);
        verify(player, never()).openInventory(any(Inventory.class));
    }

    @Test public void modeSwitchRedirectsOldMenuWithoutEditingOtherMode() {
        openPlayerCore();
        Inventory previous = top;
        settings = files.load(CombatMode.MODERN);
        click(5, ClickType.LEFT);
        assertNotSame(previous, top);
        assertTrue(edits.isEmpty());
    }

    @Test public void deletedProfileReturnsToListInsteadOfReadingMissingValues() {
        openPlayerCore();
        Inventory previous = top;
        var profiles = new HashMap<>(settings.profiles());
        profiles.remove("player");
        settings = new Settings(settings.enabled(), settings.affectOthers(), settings.respectPlayerProfile(),
                settings.preserveSyncY(), settings.botMetadata(), profiles, settings.routes(), settings.matchups(),
                settings.mode(), settings.classic(), settings.items());
        click(5, ClickType.LEFT);
        assertNotSame(previous, top);
        assertTrue(edits.isEmpty());
    }

    @Test public void dragsAndBottomInventoryClicksCannotMoveMenuItemsOrEditParameters() {
        openPlayerCore();
        var drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        gui.drag(drag);
        verify(drag).setCancelled(true);
        var click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(view);
        when(click.getClickedInventory()).thenReturn(mock(Inventory.class));
        gui.click(click);
        verify(click).setCancelled(true);
        assertTrue(edits.isEmpty());
    }

    private void openPlayerCore() {
        gui.open(player);
        click(KnockbackGui.sortedProfiles(settings).indexOf("player"), ClickType.LEFT);
        click(11, ClickType.LEFT);
    }

    private InventoryClickEvent click(int slot, ClickType type) {
        var event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(top);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getClick()).thenReturn(type);
        gui.click(event);
        return event;
    }
}
