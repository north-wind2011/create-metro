import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成屏蔽门联控器的 16×16 方块贴图（暗色金属面板 + 铜色描边 + 中央门状态屏）。
 * 用法：java -cp tools/out3 tools.ControllerTexture（从 D:\CreateMetro 运行）
 */
public class ControllerTexture {

    private static final int SIZE = 16;
    private static final String OUT =
        "src/main/resources/assets/createmetro/textures/block/platform_screen_door_controller.png";

    private static final int COPPER = 0xFFC07A42;
    private static final int COPPER_DARK = 0xFF8A5A33;
    private static final int COPPER_LIGHT = 0xFFE7B27A;
    private static final int SLATE = 0xFF2B2F33;
    private static final int SLATE_LIGHT = 0xFF3A3F44;
    private static final int SCREEN = 0xFF121517;
    private static final int SCREEN_GLOW = 0xFF7ED957;

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int color = SLATE;
                if (x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1) {
                    color = COPPER;
                } else if (x == 1 || y == 1) {
                    color = COPPER_DARK;
                }
                image.setRGB(x, y, color);
            }
        }

        // 顶部铭牌
        for (int x = 3; x <= 12; x++) {
            image.setRGB(x, 3, COPPER_LIGHT);
            image.setRGB(x, 4, SLATE_LIGHT);
        }

        // 中央屏幕（3..12 × 6..11）
        for (int y = 6; y <= 11; y++) {
            for (int x = 3; x <= 12; x++) {
                image.setRGB(x, y, SCREEN);
            }
        }
        for (int x = 3; x <= 12; x++) {
            image.setRGB(x, 6, COPPER_DARK);
            image.setRGB(x, 11, COPPER_DARK);
        }
        image.setRGB(3, 7, COPPER_DARK);
        image.setRGB(3, 8, COPPER_DARK);
        image.setRGB(3, 9, COPPER_DARK);
        image.setRGB(3, 10, COPPER_DARK);
        image.setRGB(12, 7, COPPER_DARK);
        image.setRGB(12, 8, COPPER_DARK);
        image.setRGB(12, 9, COPPER_DARK);
        image.setRGB(12, 10, COPPER_DARK);

        // 屏幕上两块“门”，中间留缝
        for (int y = 8; y <= 9; y++) {
            for (int x = 6; x <= 7; x++) {
                image.setRGB(x, y, SCREEN_GLOW);
            }
            for (int x = 9; x <= 10; x++) {
                image.setRGB(x, y, SCREEN_GLOW);
            }
        }

        // 底部散热格栅
        for (int x = 4; x <= 11; x++) {
            image.setRGB(x, 13, COPPER_DARK);
            image.setRGB(x, 14, SLATE_LIGHT);
        }

        File file = new File(OUT);
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
        System.out.println("written: " + file.getAbsolutePath());
    }
}
