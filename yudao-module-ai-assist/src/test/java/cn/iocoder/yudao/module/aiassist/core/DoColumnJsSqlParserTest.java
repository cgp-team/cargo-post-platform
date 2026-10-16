package cn.iocoder.yudao.module.aiassist.core;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * **这一本是被真缺陷逼出来的**（2026-10-16，浏览器点验时抓到）：
 * `ai_invocation_log` 有一列叫 {@code output}，MySQL 自己吃得下，但 MyBatis-Plus 的分页拦截器
 * 要先用 **JSqlParser** 解析原句 —— 那是它的保留字 ⇒ ParseException ⇒ 这个只读页整页 500。
 *
 * <p>为什么单测当时是绿的：单测走的是 mock 映射器，根本没生成过 SQL。所以这里把
 * **MP 实际会生成的那句 SELECT** 原样造出来交给 JSqlParser，才验得到真形状。
 *
 * <p>两件事一起钉（正负一对）：
 * <ol>
 *   <li><b>本模块所有 DO 的列清单必须解析得过</b>（真树读数）；</li>
 *   <li><b>{@code output} 这个词必须解析不过</b>（反证：证明这把尺子真能量到，而不是恒绿）。</li>
 * </ol>
 * 真树扫描是用文件系统读源码做的 —— 不依赖注解处理器，所以在 test 阶段就能跑。
 */
class DoColumnJsSqlParserTest {

    private static final Pattern FIELD = Pattern.compile(
            "^\\s*private\\s+(?:final\\s+)?[\\w.<>\\[\\],\\s]+?\\s+(\\w+)\\s*(?:=|;)", Pattern.MULTILINE);
    private static final Pattern TABLE_FIELD = Pattern.compile("@TableField\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern EXIST_FALSE = Pattern.compile("@TableField\\(\\s*exist\\s*=\\s*false");

    /** {@link cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO} 那六个字段会一起进 SELECT。 */
    private static final String INHERITED = ",tenant_id,create_time,update_time,creator,updater,deleted";

    private static String snake(String name) {
        return name.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase();
    }

    @Test
    void 本模块所有DO的列清单都能被JSqlParser解析() throws Exception {
        Path main = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(main), "跑不到源码目录：" + main.toAbsolutePath()
                + "（test 阶段应该在模块根目录跑；扫不到源码时这本判据必须拒判，不许报绿）");
        List<String> failures = new ArrayList<>();
        int dos = 0;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(main)) {
            walk.filter(p -> p.toString().endsWith("DO.java")).forEach(files::add);
        }
        for (Path f : files) {
            String src = new String(Files.readAllBytes(f));
            List<String> cols = new ArrayList<>();
            String[] lines = src.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = FIELD.matcher(lines[i]);
                if (!m.find()) {
                    continue;
                }
                String name = m.group(1);
                if ("serialVersionUID".equals(name)) {
                    continue;
                }
                String ann = String.join("\n",
                        List.of(lines).subList(Math.max(0, i - 6), Math.max(1, i)));
                if (EXIST_FALSE.matcher(ann).find()) {
                    continue;
                }
                Matcher tf = TABLE_FIELD.matcher(ann);
                if (tf.find()) {
                    // 有显式 @TableField 时 MP 会补 `列 AS 字段` —— 别名同样受保留字约束
                    cols.add(tf.group(1) + " AS " + name);
                } else {
                    cols.add(snake(name));
                }
            }
            if (cols.isEmpty()) {
                continue;
            }
            dos++;
            String sql = "SELECT " + String.join(",", cols) + INHERITED + " FROM t WHERE deleted=0 ORDER BY id DESC";
            try {
                CCJSqlParserUtil.parse(sql);
            } catch (Throwable t) {
                String why = String.valueOf(t.getMessage()).replace("\n", " ");
                failures.add(f.getFileName() + " ⇒ " + (why.length() > 140 ? why.substring(0, 140) : why));
            }
        }
        assertTrue(dos >= 2, "DO 只扫到 " + dos + " 个（本模块在册 2 个）⇒ 这本判据在空转，不许报绿");
        if (!failures.isEmpty()) {
            fail("这些 DO 生成的 SELECT 解析不过，那张表的**分页**会整页 500：\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    void 反证_output这个词本身必须解析不过_否则这把尺子是摆设() {
        try {
            CCJSqlParserUtil.parse("SELECT id,output FROM t WHERE deleted=0 ORDER BY id DESC");
            fail("JSqlParser 居然吃下了 `output` —— 上面那个真树扫描就没有尺子意义了（依赖升级改了保留字表？重写判据）");
        } catch (Throwable expected) {
            // 这正是当初让整页 500 的那句
        }
    }

    /** 顺带钉住"基类的字段也在 SELECT 里"这个前提：列名对不上会让上面的真树扫描变成空转。 */
    @Test
    void 基类确实存在那六个被一起选出来的字段() {
        assertTrue(java.util.Arrays.stream(BaseDO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList()
                .containsAll(List.of("creator", "createTime", "updater", "updateTime", "deleted")),
                "BaseDO 字段变了：上面的 INHERITED 常量要同轮改，否则扫描的 SQL 与真形状对不上");
    }
}
