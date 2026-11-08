import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成「屏蔽器」的 16×16 物品贴图：铜色外壳 + 暗色屏 + 两个门状态灯。
 * 用法：javac -d tools/out3 tools/LinkerTexture.java 然后 java -cp tools/out3 LinkerTexture
 */
public class LinkerTexture {

    private static final int SIZE = 16;
    private static final String OUT =
        "src/main/resources/assets/createmetro/textures/item/screen_door_linker.png";

    private static final int COPPER = 0xFFC07A42;
    private static final int COPPER_DARK = 0xFF8A5A33;
    private static final int COPPER_LIGHT = 0xFFE7B27A;
    private static final int SLATE = 0xFF30343A;
    private static final int SLATE_LIGHT = 0xFF464C54;
    private static final int SCREEN = 0xFF11161A;
    private static final int GLOW = 0xFF7ED957;

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int transparent = 0x00000000;

        // 默认全透明
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setRGB(x, y, transparent);
            }
        }

        // 天线
        for (int y = 0; y <= 3; y++) {
            image.setRGB(8, y, COPPER_LIGHT);
        }
        image.setRGB(9, 2, COPPER_DARK);

        // 机身 3..12 × 3..15
        for (int y = 3; y <= 15; y++) {
            for (int x = 3; x <= 12; x++) {
                int color = SLATE;
                if (x == 3 || x == 12 || y == 3 || y == 15) {
                    color = COPPER;
                } else if (y == 4) {
                    color = SLATE_LIGHT;
                }
                image.setRGB(x, y, color);
            }
        }

        // 屏幕 5..10 × 6..9
        for (int y = 6; y <= 9; y++) {
            for (int x = 5; x <= 10; x++) {
                image.setRGB(x, y, SCREEN);
            }
        }
        image.setRGB(5, 6, COPPER_DARK);
        image.setRGB(10, 6, COPPER_DARK);
        image.setRGB(5, 9, COPPER_DARK);
        image.setRGB(10, 9, COPPER_DARK);

        // 屏幕上的两扇门
        for (int y = 7; y <= 8; y++) {
            image.setRGB(6, y, GLOW);
            image.setRGB(9, y, GLOW);
        }

        // 按钮
        for (int y = 11; y <= 12; y++) {
            image.setRGB(5, y, COPPER_LIGHT);
            image.setRGB(10, y, COPPER_LIGHT);
        }

        // 底部握持区
        for (int x = 5; x <= 10; x++) {
            image.setRGB(x, 14, COPPER_DARK);
        }

        File file = new File(OUT);
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
        System.out.println("written: " + file.getAbsolutePath());
    }
}
