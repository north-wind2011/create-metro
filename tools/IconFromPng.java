import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 用用户提供的双向左箭头图标（双向左.png）生成屏蔽门左右导向徽标。
 *
 * 处理链（全部二值、硬边、无抗锯齿、无渐变）：
 *   1) 读 PNG，按 alpha >= 128 二值化（保留 alpha；所有不透明像素统一改为纯白 #FFFFFF）；
 *   2) 原图本身是“双向左”，左向版直接用它；右向版 = 左向版水平镜像（同一形状的镜像，不另画一份）；
 *   3) 圆环按参考图比例：徽标外径约占门宽 0.69（32px 贴图里取 22px），
 *      圈厚 1px、圈与箭头之间 1px 空隙，箭头按最大半径缩放，保证整枚箭头被圆环包住且不顶出圈外；
 *   4) 超采样 4× 后按块内占比二值化，硬边落到 22×22 的像素徽标；
 *   5) 叠到 Create 原版玻璃门贴图（32×32）上下两格交界处，居中；
 *   6) 输出 32×32 独立箭头资源与 16×16 物品图标。
 *
 * 用法：java -cp tools/out2 IconFromPng "C:\Users\north\Downloads\双向左.png"
 */
public class IconFromPng {

    private static final String SRC = "C:\\Users\\north\\Downloads\\双向左.png";
    private static final String BLOCK_OUT = "src/main/resources/assets/createmetro/textures/block/";
    private static final String ITEM_OUT = "src/main/resources/assets/createmetro/textures/item/";
    private static final String WORK = "tools/arrow-work/";
    private static final int GLASS = 32;       // 玻璃贴图尺寸
    private static final int BADGE = 22;        // 玻璃上徽标外径（参考图量得 ≈0.69 × 门宽）
    private static final int ITEM_BADGE = 7;    // 物品图标上的小箭头（物品门玻璃只有 6px 宽）
    private static final int COVER = 30;        // 物品小图标块内点亮阈值（百分比）
    private static final int SS = 4;            // 徽标超采样倍数
    private static final int WHITE = 0xFFFFFFFF;

    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args.length > 0 ? args[0] : SRC));

        // 1) 二值化（alpha >= 128 视为不透明）
        boolean[][] m = new boolean[src.getHeight()][src.getWidth()];
        for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                m[y][x] = ((src.getRGB(x, y) >>> 24) & 0xFF) >= 128;

        // 2) 裁到不透明包围盒
        m = crop(m);
        System.out.println("cropped=" + m[0].length + "x" + m.length);

        boolean[][] left = m;                 // 图标本身就是左向
        boolean[][] right = mirror(left);     // 右向 = 左向镜像

        boolean[][] badgeL = badge(left, BADGE);
        boolean[][] badgeR = badge(right, BADGE);
        System.out.println("badge=" + BADGE + "x" + BADGE);
        printAscii(badgeL, "left badge");
        printAscii(badgeR, "right badge");

        // 3) 32x32 独立资源
        BufferedImage leftAsset = canvas(badgeL, GLASS);
        BufferedImage rightAsset = canvas(badgeR, GLASS);
        ImageIO.write(leftAsset, "png", new File(BLOCK_OUT + "arrow_left.png"));
        ImageIO.write(rightAsset, "png", new File(BLOCK_OUT + "arrow_right.png"));

        // 4) 玻璃贴图（上下两格 × 左右两版）
        BufferedImage topBase = upscale(ImageIO.read(new File("tools/create-ref/textures/glass_door_top.png")), GLASS);
        BufferedImage botBase = upscale(ImageIO.read(new File("tools/create-ref/textures/glass_door_bottom.png")), GLASS);
        BufferedImage[] l = compositeGlass(topBase, botBase, badgeL);
        BufferedImage[] r = compositeGlass(topBase, botBase, badgeR);
        ImageIO.write(l[0], "png", new File(BLOCK_OUT + "platform_screen_door_glass_top_left.png"));
        ImageIO.write(l[1], "png", new File(BLOCK_OUT + "platform_screen_door_glass_bottom_left.png"));
        ImageIO.write(r[0], "png", new File(BLOCK_OUT + "platform_screen_door_glass_top_right.png"));
        ImageIO.write(r[1], "png", new File(BLOCK_OUT + "platform_screen_door_glass_bottom_right.png"));

        // 5) 物品图标（16x16 门图标 + 7px 箭头，环会糊成一团，这里只保留箭头本体）
        BufferedImage itemBase = ImageIO.read(new File("tools/create-ref/textures/glass_door.png"));
        ImageIO.write(compositeItem(itemBase, sample(left, ITEM_BADGE)), "png",
            new File(ITEM_OUT + "platform_screen_door_left.png"));
        ImageIO.write(compositeItem(itemBase, sample(right, ITEM_BADGE)), "png",
            new File(ITEM_OUT + "platform_screen_door_right.png"));

        // 6) 8 倍预览
        savePreview(l, WORK + "door_left.png");
        savePreview(r, WORK + "door_right.png");
        saveScaled(leftAsset, WORK + "arrow_left_8x.png", 8);
        saveScaled(rightAsset, WORK + "arrow_right_8x.png", 8);
        System.out.println("done");
    }

    /**
     * 生成 size×size 的圆环徽标：圆环包住整枚箭头，圈厚 1px、空隙 1px。
     */
    private static boolean[][] badge(boolean[][] arrow, int size) {
        int h = arrow.length, w = arrow[0].length;
        double scx = w / 2.0, scy = h / 2.0;

        // 箭头到包围盒中心的最大半径（角上的尖端），用它做等比缩放
        double rmax = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (arrow[y][x])
                    rmax = Math.max(rmax, Math.hypot(x + 0.5 - scx, y + 0.5 - scy));
        if (rmax <= 0) rmax = 1;

        int n = size * SS;
        double c = n / 2.0;
        double outer = size * SS / 2.0;            // 圆环外半径
        double ringInner = outer - SS;             // 圈厚 1px
        double arrowR = ringInner - SS;            // 圈与箭头间空隙 1px

        boolean[][] ring = new boolean[n][n];
        boolean[][] core = new boolean[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x + 0.5 - c, dy = y + 0.5 - c;
                double r = Math.hypot(dx, dy);
                if (r >= ringInner && r <= outer) ring[y][x] = true;
                if (r <= arrowR) {
                    int sx = (int) Math.floor(scx + dx * (rmax / arrowR));
                    int sy = (int) Math.floor(scy + dy * (rmax / arrowR));
                    if (sx >= 0 && sy >= 0 && sx < w && sy < h) core[y][x] = arrow[sy][sx];
                }
            }
        }

        boolean[][] out = new boolean[size][size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int ringOn = 0, coreOn = 0;
                for (int sy = 0; sy < SS; sy++)
                    for (int sx = 0; sx < SS; sx++) {
                        if (ring[y * SS + sy][x * SS + sx]) ringOn++;
                        if (core[y * SS + sy][x * SS + sx]) coreOn++;
                    }
                // 圆环阈值低，保证 1px 细圈连续不断；箭头阈值略高，保持硬边
                out[y][x] = ringOn >= 2 || coreOn >= 4;
            }
        }
        return out;
    }

    private static boolean[][] mirror(boolean[][] m) {
        int h = m.length, w = m[0].length;
        boolean[][] o = new boolean[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                o[y][w - 1 - x] = m[y][x];
        return o;
    }

    private static boolean[][] crop(boolean[][] m) {
        int minX = 9999, minY = 9999, maxX = -1, maxY = -1;
        for (int y = 0; y < m.length; y++)
            for (int x = 0; x < m[y].length; x++)
                if (m[y][x]) { minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
        int w = maxX - minX + 1, h = maxY - minY + 1;
        boolean[][] o = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) o[y][x] = m[minY + y][minX + x];
        return o;
    }

    /** 块内占比采样到 size×size（按原图比例居中，硬边二值）。 */
    private static boolean[][] sample(boolean[][] m, int size) {
        int h = m.length, w = m[0].length;
        boolean[][] o = new boolean[size][size];
        for (int oy = 0; oy < size; oy++) {
            for (int ox = 0; ox < size; ox++) {
                int ya = (int) Math.floor(oy * h / (double) size);
                int yb = (int) Math.floor((oy + 1) * h / (double) size);
                int xa = (int) Math.floor(ox * w / (double) size);
                int xb = (int) Math.floor((ox + 1) * w / (double) size);
                if (yb <= ya) yb = ya + 1;
                if (xb <= xa) xb = xa + 1;
                int on = 0, total = 0;
                for (int y = ya; y < yb && y < h; y++)
                    for (int x = xa; x < xb && x < w; x++) { total++; if (m[y][x]) on++; }
                o[oy][ox] = total > 0 && on * 100 / total >= COVER;
            }
        }
        return o;
    }

    /** 把二值徽标居中画到 size×size 画布。 */
    private static BufferedImage canvas(boolean[][] m, int size) {
        int s = m.length;
        int off = (size - s) / 2;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++)
                if (m[y][x]) out.setRGB(x + off, y + off, WHITE);
        return out;
    }

    private static BufferedImage upscale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                out.setRGB(x, y, src.getRGB(x * src.getWidth() / size, y * src.getHeight() / size));
        return out;
    }

    /** 徽标贴在两格交界处；y>=32 属于上格贴图行（r → 63-y），否则属于下格（r → 31-y）。 */
    private static BufferedImage[] compositeGlass(BufferedImage top, BufferedImage bottom, boolean[][] icon) {
        BufferedImage t = copy(top), b = copy(bottom);
        int s = icon.length;
        int ox = (GLASS - s) / 2, oy = GLASS - s / 2;
        for (int iy = 0; iy < s; iy++)
            for (int ix = 0; ix < s; ix++) {
                if (!icon[iy][ix]) continue;
                int px = ox + ix, py = oy + iy;
                if (py >= GLASS) t.setRGB(px, (GLASS * 2 - 1) - py, WHITE);
                else b.setRGB(px, GLASS - 1 - py, WHITE);
            }
        return new BufferedImage[] { t, b };
    }

    private static BufferedImage compositeItem(BufferedImage base, boolean[][] icon) {
        BufferedImage out = copy(base);
        int s = icon.length;
        int off = (16 - s) / 2;
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++)
                if (icon[y][x]) out.setRGB(off + x, off + y, WHITE);
        return out;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    private static void savePreview(BufferedImage[] pair, String path) throws Exception {
        BufferedImage door = new BufferedImage(GLASS, GLASS * 2, BufferedImage.TYPE_INT_RGB);
        // 玻璃是透明的，预览铺一层深色底才能看清白色图标
        java.awt.Graphics2D g = door.createGraphics();
        g.setColor(new java.awt.Color(0x2B2B2B));
        g.fillRect(0, 0, door.getWidth(), door.getHeight());
        g.drawImage(pair[0], 0, 0, null);
        g.drawImage(pair[1], 0, GLASS, null);
        g.dispose();
        saveScaled(door, path, 8);
    }

    private static void saveScaled(BufferedImage src, String path, int scale) throws Exception {
        BufferedImage big = new BufferedImage(src.getWidth() * scale, src.getHeight() * scale,
            BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < big.getHeight(); y++)
            for (int x = 0; x < big.getWidth(); x++)
                big.setRGB(x, y, src.getRGB(x / scale, y / scale));
        File out = new File(path);
        out.getParentFile().mkdirs();
        ImageIO.write(big, "png", out);
    }

    private static void printAscii(boolean[][] m, String title) {
        System.out.println(title + ":");
        for (boolean[] row : m) {
            StringBuilder sb = new StringBuilder();
            for (boolean v : row) sb.append(v ? '#' : '.');
            System.out.println(sb);
        }
    }
}
