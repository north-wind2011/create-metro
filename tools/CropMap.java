import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 打印指定区域的 ASCII 亮度图，并输出 8x 放大预览。 */
public class CropMap {
    public static void main(String[] args) throws Exception {
        String src = args[0];
        int x0 = Integer.parseInt(args[1]);
        int y0 = Integer.parseInt(args[2]);
        int w = Integer.parseInt(args[3]);
        int h = Integer.parseInt(args[4]);
        BufferedImage img = ImageIO.read(new File(src));
        System.out.println("region " + x0 + "," + y0 + " " + w + "x" + h);
        for (int y = y0; y < Math.min(y0 + h, img.getHeight()); y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = x0; x < Math.min(x0 + w, img.getWidth()); x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                char c = max > 190 ? '#' : max > 120 ? '+' : max > 70 ? ':' : '.';
                sb.append(c);
            }
            System.out.println(String.format("%3d %s", y, sb));
        }

        BufferedImage crop = img.getSubimage(x0, y0, Math.min(w, img.getWidth() - x0),
            Math.min(h, img.getHeight() - y0));
        BufferedImage big = new BufferedImage(crop.getWidth() * 8, crop.getHeight() * 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < big.getHeight(); y++) {
            for (int x = 0; x < big.getWidth(); x++) {
                big.setRGB(x, y, crop.getRGB(x / 8, y / 8));
            }
        }
        File out = new File("tools/arrow-work/crop_8x.png");
        out.getParentFile().mkdirs();
        ImageIO.write(big, "png", out);
        System.out.println("preview: " + out);
    }
}
