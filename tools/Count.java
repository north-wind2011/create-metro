import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 校验门玻璃贴图里浅灰图标像素的数量与包围盒。 */
public class Count {
    public static void main(String[] args) throws Exception {
        for (String p : args) {
            BufferedImage img = ImageIO.read(new File(p));
            int n = 0, lo = 99, hi = -1, top = 99, bot = -1;
            for (int y = 0; y < img.getHeight(); y++)
                for (int x = 0; x < img.getWidth(); x++) {
                    int a = img.getRGB(x, y);
                    int r = (a >> 16) & 0xFF;
                    if (r < 230 && ((a >>> 24) != 0) ) { 
                        int g = (a >> 8) & 255, b = a & 255;
                        if (Math.abs(r - g) < 12 && Math.abs(g - b) < 12) {
                            n++; lo = Math.min(lo, x); hi = Math.max(hi, x);
                            top = Math.min(top, y); bot = Math.max(bot, y);
                        }
                    }
                }
            System.out.println(p + " iconPixels=" + n + " bbox=" + lo + "," + top + " " + hi + "," + bot);
        }
    }
}
