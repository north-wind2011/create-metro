// 由于当前网络无法访问 maven.neoforged.net，而 ModDevGradle 调用 NeoFormRuntime 时
// 不会透传 Maven 仓库参数，这里包一层 NFRT 启动器，把可访问的镜像仓库注入进去。
//
// 该文件由 tools/nfrt-launcher/build-launcher.ps1 编译，并与 NFRT 官方 all.jar 合并为
// tools/nfrt-launcher/neoform-runtime-launcher.jar。
public final class NfrtLauncher {
    private static final String[] REPOSITORIES = {
        "https://repo.maven.apache.org/maven2/",
        "https://libraries.minecraft.net/",
        "https://repo.stellardrift.ca/maven/internal/",
        "https://mirror4.nyist.edu.cn/bmclapi/"
    };

    private NfrtLauncher() {
    }

    public static void main(String[] args) throws Exception {
        String[] merged = new String[REPOSITORIES.length + args.length];
        int i = 0;
        for (String repository : REPOSITORIES) {
            merged[i++] = "--repository=" + repository;
        }
        System.arraycopy(args, 0, merged, i, args.length);
        net.neoforged.neoform.runtime.cli.Main.main(merged);
    }
}
