import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 调试工具：打印图标包围盒的亮度 ASCII，便于核对阈值与形状。 */
public class IconDump {
    public static void main(String[] args) throws Exception {
        String src = args[0];
        int sx = Integer.parseInt(args[1]);
        int sy = Integer.parseInt(args[2]);
        int sw = Integer.parseInt(args[3]);
        int sh = Integer.parseInt(args[4]);
        BufferedImage img = ImageIO.read(new File(src));

        // 背景亮度参考：取种子区域四角
        double bg = 0;
        int[] corners = { img.getRGB(sx, sy), img.getRGB(sx + sw - 1, sy),
            img.getRGB(sx, sy + sh - 1), img.getRGB(sx + sw - 1, sy + sh - 1) };
        for (int c : corners) bg += lum(c);
        bg /= 4;
        System.out.println("bg=" + bg);

        int t = (int) (bg + 28);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = sy; y < sy + sh; y++) {
            for (int x = sx; x < sx + sw; x++) {
                if (lum(img.getRGB(x, y)) < t) continue;
                minX = Math.min(minX, x); minY = Math.min(minY, y);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
        }
        int bw = maxX - minX + 1, bh = maxY - minY + 1;
        System.out.println("threshold=" + t + " bbox=" + minX + "," + minY + " " + bw + "x" + bh);

        BufferedImage crop = img.getSubimage(minX, minY, bw, bh);
        for (int y = 0; y < bh; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < bw; x++) {
                int v = (int) lum(crop.getRGB(x, y));
                sb.append(v >= t + 45 ? '#' : v >= t + 15 ? '+' : v >= t ? '-' : '.');
            }
            System.out.println(String.format("%3d %s", y, sb));
        }

        BufferedImage big = new BufferedImage(bw * 10, bh * 10, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < big.getHeight(); y++)
            for (int x = 0; x < big.getWidth(); x++)
                big.setRGB(x, y, crop.getRGB(x / 10, y / 10));
        File out = new File("tools/arrow-work/dump_10x.png");
        out.getParentFile().mkdirs();
        ImageIO.write(big, "png", out);
    }

    private static double lum(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
}
