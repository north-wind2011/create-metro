package com.createmetro.client;

import com.createmetro.content.controller.PlatformScreenDoorControllerMenu;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.fml.ModList;

/**
 * 联控器 GUI：极简浅色控制盘（D 风格）。
 *
 * <p>浅灰白底 + 1px 深灰细边，不用铜色、拉丝金属和螺丝。顶部左侧深色粗体标题，
 * 右侧小号灰字「已连接 N 对 · 维护 M 位」，下方 1px 浅灰分隔线。中部为分栏表头
 * 「门位 / 左门 / 右门 / 状态 / 维护」，行高 22、只画 1px 极细行间线，不画斑马纹；
 * 状态用小色块 + 文字，维护列是 12×12 无边框复选框。底部 1px 分隔线 + 左侧灰色状态栏，
 * 右侧两个浅灰底、圆角 2px 的轻量按钮。门位多时滚轮滚动，3px 浅灰滚动条。</p>
 */
public class PlatformScreenDoorControllerScreen
    extends AbstractSimiContainerScreen<PlatformScreenDoorControllerMenu> {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 240;

    // 浅色极简配色
    private static final int COLOR_BG = 0xFFF2F3F5;
    private static final int COLOR_BORDER = 0xFFC8CBD0;
    private static final int COLOR_DIVIDER = 0xFFE3E5E9;
    private static final int COLOR_ROW_LINE = 0xFFE3E5E9;
    private static final int COLOR_TITLE = 0xFF23262A;
    private static final int COLOR_TEXT = 0xFF33383D;
    private static final int COLOR_TEXT_DIM = 0xFF6B7075;
    private static final int COLOR_CELL_TEXT = 0xFF4A4F54;
    private static final int COLOR_OPEN = 0xFF2E9E5B;
    private static final int COLOR_CLOSED = 0xFFD9534F;
    private static final int COLOR_AMBER = 0xFFC79A2E;
    private static final int COLOR_BUTTON = 0xFFE7E9EC;
    private static final int COLOR_BUTTON_HOVER = 0xFFDDE0E4;
    private static final int COLOR_SCROLL = 0xFFC8CBD0;

    private static final int ROW_HEIGHT = 22;
    private static final int VISIBLE_ROWS = 5;
    private static final int TABLE_TOP_OFFSET = 70;
    private static final int TABLE_BOTTOM_OFFSET = 180;
    private static final int TABLE_LEFT_OFFSET = 8;
    private static final int TABLE_RIGHT_OFFSET = 350;

    // 分栏：门位 / 左门 / 右门 / 状态 / 列车联动 / 维护
    private static final int COL_SLOT = 12;
    private static final int COL_LEFT = 88;
    private static final int COL_RIGHT = 140;
    private static final int COL_STATE = 192;
    private static final int COL_TRAIN = 242;
    private static final int COL_MAINT = 298;
    private static final int COL_END = 346;

    private static final int BUTTON_WIDTH = 76;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 8;
    private static final int BUTTON_Y_OFFSET = 194;

    private int tableTop;
    private int scrollOffset;
    private int pressedButton = -1;
    private String modVersion = "0.0.0";

    public PlatformScreenDoorControllerScreen(PlatformScreenDoorControllerMenu menu, Inventory inventory,
                                              Component title) {
        super(menu, inventory, title);
        setWindowSize(WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        tableTop = topPos + TABLE_TOP_OFFSET;
        scrollOffset = 0;
        pressedButton = -1;
        ModList.get().getModContainerById("createmetro").ifPresent(
            container -> modVersion = container.getModInfo().getVersion().toString());
    }

    // ---------------------------------------------------------------- 面板

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        clampScroll();

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, COLOR_BG);
        graphics.fill(x, y, x + WIDTH, y + 1, COLOR_BORDER);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, COLOR_BORDER);
        graphics.fill(x, y, x + 1, y + HEIGHT, COLOR_BORDER);
        graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + HEIGHT, COLOR_BORDER);

        drawHeader(graphics, x, y);
        drawTable(graphics, x, y, mouseX, mouseY);
        drawStatusBar(graphics, x, y, mouseX, mouseY);
    }

    // ---------------------------------------------------------------- 顶部信息区

    private void drawHeader(GuiGraphics graphics, int x, int y) {
        Component title = Component.translatable("gui.createmetro.controller.panel_title");
        graphics.drawString(font, title, x + 12, y + 12, COLOR_TITLE, false);
        graphics.drawString(font, title, x + 13, y + 12, COLOR_TITLE, false);

        Component pairs = Component.translatable("gui.createmetro.controller.connected_pairs",
            menu.getPairCount());
        Component maint = Component.translatable("gui.createmetro.controller.status_maint",
            menu.getMaintenanceCount());
        int rightWidth = font.width(pairs) + font.width(" · ") + font.width(maint);
        int rightX = x + TABLE_RIGHT_OFFSET - rightWidth;
        graphics.drawString(font, pairs, rightX, y + 14, COLOR_TEXT_DIM, false);
        int sepX = rightX + font.width(pairs);
        graphics.drawString(font, " · ", sepX, y + 14, COLOR_TEXT_DIM, false);
        graphics.drawString(font, maint, sepX + font.width(" · "), y + 14, COLOR_TEXT_DIM, false);

        graphics.fill(x + 12, y + 30, x + TABLE_RIGHT_OFFSET, y + 31, COLOR_DIVIDER);
    }

    // ---------------------------------------------------------------- 分栏表格

    private void drawTable(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int left = x + TABLE_LEFT_OFFSET;
        int right = x + TABLE_RIGHT_OFFSET;

        drawColumnLabel(graphics, x, y + 55, COL_SLOT, "gui.createmetro.controller.col.slot");
        drawColumnLabel(graphics, x, y + 55, COL_LEFT, "gui.createmetro.controller.col.left");
        drawColumnLabel(graphics, x, y + 55, COL_RIGHT, "gui.createmetro.controller.col.right");
        drawColumnLabel(graphics, x, y + 55, COL_STATE, "gui.createmetro.controller.col.state");
        drawColumnLabel(graphics, x, y + 55, COL_TRAIN, "gui.createmetro.controller.col.train_link");
        drawColumnLabel(graphics, x, y + 55, COL_MAINT, "gui.createmetro.controller.col.maint");
        graphics.fill(left, y + 67, right, y + 68, COLOR_DIVIDER);

        int rows = menu.getRowCount();
        if (rows == 0) {
            graphics.drawString(font, Component.translatable("gui.createmetro.controller.empty"),
                left + 10, tableTop + 9, COLOR_TEXT_DIM, false);
            drawScrollbar(graphics, x, rows);
            return;
        }
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int row = scrollOffset + i;
            if (row >= rows) {
                break;
            }
            drawRow(graphics, x, row, tableTop + i * ROW_HEIGHT, mouseX, mouseY);
        }

        // 滚动提示（列头右端）
        if (menu.isTruncated()) {
            Component truncated = Component.translatable("gui.createmetro.controller.truncated",
                PlatformScreenDoorControllerMenu.MAX_ROWS);
            graphics.drawString(font, truncated, right - 6 - font.width(truncated), y + 55,
                COLOR_AMBER, false);
        } else if (rows > VISIBLE_ROWS) {
            Component pos = Component.translatable("gui.createmetro.controller.scroll_pos",
                scrollOffset + 1, rows);
            graphics.drawString(font, pos, right - 6 - font.width(pos), y + 55, COLOR_TEXT_DIM, false);
        }
        drawScrollbar(graphics, x, rows);
    }

    private void drawColumnLabel(GuiGraphics graphics, int x, int y, int columnX, String key) {
        graphics.drawString(font, Component.translatable(key), x + columnX + 4, y, COLOR_TEXT_DIM, false);
    }

    private void drawRow(GuiGraphics graphics, int x, int row, int ry, int mouseX, int mouseY) {
        boolean maintenance = menu.isRowMaintenance(row);
        int left = x + TABLE_LEFT_OFFSET;
        int right = x + TABLE_RIGHT_OFFSET;
        graphics.fill(left, ry, right, ry + ROW_HEIGHT - 1, COLOR_BG);
        graphics.fill(left, ry + ROW_HEIGHT - 1, right, ry + ROW_HEIGHT, COLOR_ROW_LINE);

        BlockPos first = menu.getRowFirstPos(row);
        double distance = Math.sqrt(first.distSqr(menu.getControllerPos()));
        Component slotNo = Component.literal(String.format("%02d", row + 1));
        graphics.drawString(font, slotNo, x + COL_SLOT + 6, ry + 7, COLOR_TEXT, false);
        Component dist = Component.literal(String.format("%.0fm", distance));
        graphics.drawString(font, dist, x + COL_SLOT + 28, ry + 7, COLOR_TEXT_DIM, false);

        boolean firstOpen = menu.isRowFirstOpen(row);
        drawDoorCell(graphics, x + COL_LEFT, ry, firstOpen);
        if (menu.hasRowSecond(row)) {
            drawDoorCell(graphics, x + COL_RIGHT, ry, menu.isRowSecondOpen(row));
        } else {
            graphics.drawString(font, "—", x + COL_RIGHT + 18, ry + 7, COLOR_TEXT_DIM, false);
        }

        // 状态列：8×8 色块 + 文字
        int stateIndex = pairState(row);
        int stateColor = stateColor(stateIndex);
        drawChip(graphics, x + COL_STATE + 4, ry + 7, stateColor);
        graphics.drawString(font, Component.translatable(stateKey(stateIndex)),
            x + COL_STATE + 16, ry + 7, stateColor, false);

        // 列车联动列：12×12 复选框，默认不勾
        boolean trainHovered = isHovered(mouseX, mouseY, x + COL_TRAIN, ry,
            COL_MAINT - COL_TRAIN, ROW_HEIGHT);
        drawCheckbox(graphics, x + COL_TRAIN + (COL_MAINT - COL_TRAIN - 12) / 2, ry + 5,
            menu.isRowTrainLinked(row), trainHovered);

        // 维护列：12×12 无边框复选框
        boolean hovered = isHovered(mouseX, mouseY, x + COL_MAINT, ry, COL_END - COL_MAINT, ROW_HEIGHT);
        drawCheckbox(graphics, x + COL_MAINT + 4, ry + 5, maintenance, hovered);
    }

    private void drawDoorCell(GuiGraphics graphics, int columnX, int ry, boolean open) {
        drawChip(graphics, columnX + 4, ry + 8, open ? COLOR_OPEN : COLOR_CLOSED, 6);
        graphics.drawString(font, Component.translatable(open
                ? "gui.createmetro.controller.cell.open_short"
                : "gui.createmetro.controller.cell.closed_short"),
            columnX + 16, ry + 7, COLOR_CELL_TEXT, false);
    }

    /** 0=已开启 1=已关闭 2=半开 */
    private int pairState(int row) {
        boolean first = menu.isRowFirstOpen(row);
        boolean second = menu.hasRowSecond(row) && menu.isRowSecondOpen(row);
        if (first && (second || !menu.hasRowSecond(row))) {
            return 0;
        }
        if (!first && !second) {
            return 1;
        }
        return 2;
    }

    private static int stateColor(int index) {
        return switch (index) {
            case 0 -> COLOR_OPEN;
            case 2 -> COLOR_AMBER;
            default -> COLOR_CLOSED;
        };
    }

    private static String stateKey(int index) {
        return switch (index) {
            case 0 -> "gui.createmetro.controller.cell.open";
            case 2 -> "gui.createmetro.controller.cell.half";
            default -> "gui.createmetro.controller.cell.closed";
        };
    }

    private static void drawChip(GuiGraphics graphics, int x, int y, int color) {
        drawChip(graphics, x, y, color, 8);
    }

    private static void drawChip(GuiGraphics graphics, int x, int y, int color, int size) {
        graphics.fill(x, y, x + size, y + size, color);
    }

    /** 无边框复选框：浅灰描边方框，选中为深灰勾。 */
    private static void drawCheckbox(GuiGraphics graphics, int x, int y, boolean checked, boolean hovered) {
        int border = hovered ? COLOR_TEXT_DIM : COLOR_BORDER;
        graphics.fill(x, y, x + 12, y + 12, border);
        graphics.fill(x + 1, y + 1, x + 11, y + 11, COLOR_BG);
        if (checked) {
            graphics.fill(x + 2, y + 6, x + 5, y + 9, COLOR_TITLE);
            graphics.fill(x + 4, y + 4, x + 8, y + 7, COLOR_TITLE);
            graphics.fill(x + 7, y + 2, x + 11, y + 5, COLOR_TITLE);
        }
    }

    private void drawScrollbar(GuiGraphics graphics, int x, int rows) {
        int trackTop = tableTop;
        int trackHeight = VISIBLE_ROWS * ROW_HEIGHT;
        int sx = x + TABLE_RIGHT_OFFSET + 3;
        int maxScroll = Math.max(0, rows - VISIBLE_ROWS);
        if (maxScroll == 0) {
            return;
        }
        int thumbHeight = Math.max(14, trackHeight * VISIBLE_ROWS / rows);
        int thumbY = trackTop + (trackHeight - thumbHeight) * scrollOffset / maxScroll;
        graphics.fill(sx, thumbY, sx + 3, thumbY + thumbHeight, COLOR_SCROLL);
    }

    // ---------------------------------------------------------------- 底部状态栏

    private void drawStatusBar(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        graphics.fill(x + 8, y + 186, x + TABLE_RIGHT_OFFSET, y + 187, COLOR_DIVIDER);

        Component status = Component.translatable("gui.createmetro.controller.status_bar",
            menu.getPairCount(), menu.getMaintenanceCount(), modVersion);
        graphics.drawString(font, status, x + 12, y + 199, COLOR_TEXT_DIM, false);

        int closeX = x + TABLE_RIGHT_OFFSET - BUTTON_WIDTH;
        int openX = closeX - BUTTON_GAP - BUTTON_WIDTH;
        drawFlatButton(graphics, openX, y + BUTTON_Y_OFFSET, Component.translatable(
            "gui.createmetro.controller.btn_open_all"),
            isHovered(mouseX, mouseY, openX, y + BUTTON_Y_OFFSET, BUTTON_WIDTH, BUTTON_HEIGHT),
            pressedButton == PlatformScreenDoorControllerMenu.BUTTON_OPEN_ALL);
        drawFlatButton(graphics, closeX, y + BUTTON_Y_OFFSET, Component.translatable(
            "gui.createmetro.controller.btn_close_all"),
            isHovered(mouseX, mouseY, closeX, y + BUTTON_Y_OFFSET, BUTTON_WIDTH, BUTTON_HEIGHT),
            pressedButton == PlatformScreenDoorControllerMenu.BUTTON_CLOSE_ALL);
    }

    private void drawFlatButton(GuiGraphics graphics, int x, int y, Component label,
                                boolean hovered, boolean pressed) {
        int top = y + (pressed ? 1 : 0);
        int background = pressed ? COLOR_BUTTON_HOVER : (hovered ? COLOR_BUTTON_HOVER : COLOR_BUTTON);
        graphics.fill(x, top, x + BUTTON_WIDTH, top + BUTTON_HEIGHT, background);
        // 圆角 2px：抹掉四角
        graphics.fill(x, top, x + 1, top + 1, COLOR_BG);
        graphics.fill(x + BUTTON_WIDTH - 1, top, x + BUTTON_WIDTH, top + 1, COLOR_BG);
        graphics.fill(x, top + BUTTON_HEIGHT - 1, x + 1, top + BUTTON_HEIGHT, COLOR_BG);
        graphics.fill(x + BUTTON_WIDTH - 1, top + BUTTON_HEIGHT - 1, x + BUTTON_WIDTH,
            top + BUTTON_HEIGHT, COLOR_BG);
        int textX = x + (BUTTON_WIDTH - font.width(label)) / 2;
        graphics.drawString(font, label, textX, top + 5, COLOR_TEXT, false);
    }

    // ---------------------------------------------------------------- 交互

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题由自绘控制盘取代。
    }

    @Override
    protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 没有 Create 控件/物品槽；只给「列车联动」复选框画一个轻量悬停提示。
        int rows = menu.getRowCount();
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int row = scrollOffset + i;
            if (row >= rows) {
                break;
            }
            int ry = tableTop + i * ROW_HEIGHT;
            if (isHovered(mouseX, mouseY, leftPos + COL_TRAIN, ry,
                COL_MAINT - COL_TRAIN, ROW_HEIGHT)) {
                Component tip = Component.translatable(menu.isRowTrainLinked(row)
                    ? "gui.createmetro.controller.train_link.hint_on"
                    : "gui.createmetro.controller.train_link.hint_off");
                drawTooltip(graphics, tip, mouseX, mouseY);
                return;
            }
        }
    }

    /** 浅色小提示框：浅灰底 + 1px 深灰边，跟随鼠标但不越出面板。 */
    private void drawTooltip(GuiGraphics graphics, Component text, int mouseX, int mouseY) {
        int width = font.width(text);
        int tx = Math.min(mouseX + 10, leftPos + WIDTH - width - 8);
        tx = Math.max(leftPos + 4, tx);
        int ty = mouseY - 14;
        if (ty < topPos + 2) {
            ty = mouseY + 12;
        }
        graphics.fill(tx - 3, ty - 2, tx + width + 3, ty + 10, COLOR_BUTTON);
        graphics.fill(tx - 3, ty - 2, tx + width + 3, ty - 1, COLOR_BORDER);
        graphics.fill(tx - 3, ty + 9, tx + width + 3, ty + 10, COLOR_BORDER);
        graphics.fill(tx - 3, ty - 2, tx - 2, ty + 10, COLOR_BORDER);
        graphics.fill(tx + width + 2, ty - 2, tx + width + 3, ty + 10, COLOR_BORDER);
        graphics.drawString(font, text, tx, ty, COLOR_TEXT, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int by = topPos + BUTTON_Y_OFFSET;
            int closeX = leftPos + TABLE_RIGHT_OFFSET - BUTTON_WIDTH;
            int openX = closeX - BUTTON_GAP - BUTTON_WIDTH;
            if (isHovered((int) mouseX, (int) mouseY, openX, by, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                pressedButton = PlatformScreenDoorControllerMenu.BUTTON_OPEN_ALL;
                sendButton(PlatformScreenDoorControllerMenu.BUTTON_OPEN_ALL);
                return true;
            }
            if (isHovered((int) mouseX, (int) mouseY, closeX, by, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                pressedButton = PlatformScreenDoorControllerMenu.BUTTON_CLOSE_ALL;
                sendButton(PlatformScreenDoorControllerMenu.BUTTON_CLOSE_ALL);
                return true;
            }
            // 维护列的复选框
            int rows = menu.getRowCount();
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int row = scrollOffset + i;
                if (row >= rows) {
                    break;
                }
                int ry = tableTop + i * ROW_HEIGHT;
                if (mouseX >= leftPos + COL_TRAIN && mouseX < leftPos + COL_MAINT
                    && mouseY >= ry && mouseY < ry + ROW_HEIGHT) {
                    sendButton(PlatformScreenDoorControllerMenu.BUTTON_TRAIN_LINK_BASE + row);
                    return true;
                }
                if (mouseX >= leftPos + COL_MAINT && mouseX < leftPos + COL_END
                    && mouseY >= ry && mouseY < ry + ROW_HEIGHT) {
                    sendButton(PlatformScreenDoorControllerMenu.BUTTON_MAINTENANCE_BASE + row);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        pressedButton = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, menu.getRowCount() - VISIBLE_ROWS);
        int left = leftPos + TABLE_LEFT_OFFSET;
        int right = leftPos + TABLE_RIGHT_OFFSET;
        int top = topPos + 68;
        int bottom = topPos + TABLE_BOTTOM_OFFSET;
        if (maxScroll > 0 && mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom) {
            int next = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.signum(scrollY)));
            if (next != scrollOffset) {
                scrollOffset = next;
                playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f, 1.5f);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, menu.getRowCount() - VISIBLE_ROWS);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    private void sendButton(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            playUiSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f);
        }
    }

    private static boolean isHovered(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
