import com.heyu.notes.Block
import com.heyu.notes.Inl
import com.heyu.notes.Md
import com.heyu.notes.Note
import com.heyu.notes.Json

var pass = 0
var fail = 0

fun ok(cond: Boolean, name: String, extra: String = "") {
    if (cond) { pass++; println("  PASS $name") } else { fail++; println("  FAIL $name $extra") }
}

fun main() {
    println("== 标题")
    val b1 = Md.parse("# 一级\n\n## 二级\n\n### 三级")
    ok(b1.size == 3, "三个标题块", "size=${b1.size}")
    ok((b1[0] as? Block.Heading)?.level == 1 && (b1[0] as Block.Heading).text == "一级", "H1 解析")
    ok((b1[2] as Block.Heading).level == 3, "H3 解析")

    println("== 段落与换行")
    val b2 = Md.parse("第一行\n第二行\n\n新段落")
    ok(b2.size == 2, "两个段落", "size=${b2.size}")
    ok((b2[0] as Block.Para).text.contains("第一行") && (b2[0] as Block.Para).text.contains("第二行"), "段内换行保留")

    println("== 列表 / 任务 / 缩进")
    val b3 = Md.parse("- 苹果\n- 香蕉\n  - 小香蕉\n\n1. 一\n2. 二\n\n- [x] 完成\n- [ ] 未完成")
    val lists = b3.filterIsInstance<Block.Bullets>()
    ok(lists.size == 3, "三个列表块", "size=${lists.size}")
    ok(lists[0].items.size == 3, "无序列表 3 项")
    ok(lists[0].items[2].level == 1, "子项缩进识别")
    ok(lists[1].ordered && lists[1].items[1].number == 2, "有序列表编号")
    ok(lists[2].items[0].checked == true && lists[2].items[1].checked == false, "任务清单状态")

    println("== 代码块")
    val b4 = Md.parse("```kotlin\nval a = 1\nprintln(a)\n```")
    ok(b4.size == 1, "代码块一个", "size=${b4.size}")
    val code = b4[0] as Block.Code
    ok(code.lang == "kotlin" && code.code.contains("val a = 1"), "代码内容与语言", code.lang)

    println("== 引用 / 分隔线")
    val b5 = Md.parse("> 引用第一行\n> 引用第二行\n\n---\n\n正文")
    ok(b5.any { it is Block.Quote }, "引用块")
    ok((b5.first { it is Block.Quote } as Block.Quote).text.contains("引用第二行"), "引用合并多行")
    ok(b5.any { it is Block.Hr }, "分隔线")

    println("== 表格")
    val b6 = Md.parse("| 项目 | 本地 |\n| --- | --- |\n| 加密 | 支持 |\n| 增量 | 支持 |")
    ok(b6.size == 1, "表格一个块", "size=${b6.size}")
    val tb = b6[0] as Block.Table
    ok(tb.header.size == 2 && tb.header[0] == "项目", "表头")
    ok(tb.rows.size == 2 && tb.rows[1][1] == "支持", "表格行")

    println("== 脚注")
    val b7 = Md.parse("正文[^1]\n\n[^1]: 脚注内容")
    ok(b7.none { it is Block.Para && it.text.startsWith("[^1]:") }, "脚注定义不当作正文")

    println("== 标题推导 / 纯文本")
    ok(Md.title("# 周末计划\n\n内容") == "周末计划", "title 取首个标题")
    ok(Md.title("没有标题的正文") == "没有标题的正文", "title 兜底首行")
    val plain = Md.plainText("# 标题\n\n- [x] 完成\n\n**加粗** 和 `代码`\n\n```js\nconst a=1\n```")
    ok(!plain.contains("#") && !plain.contains("**") && !plain.contains("const a"), "plainText 去语法", plain)

    println("== 全语法（commonmark + GFM）")
    val b8 = Md.parse("~~删除~~ 和 https://example.com 以及 [链接](https://a.b)")
    val inl8 = (b8[0] as Block.Para).inl
    ok(inl8.any { it is Inl.Strike }, "删除线")
    ok(inl8.count { it is Inl.Link } >= 2, "自动链接 + 行内链接")
    val b9 = Md.parse("> 外层\n> > 内层")
    ok((b9[0] as Block.Quote).blocks.any { it is Block.Quote }, "嵌套引用")
    val b10 = Md.parse("<div>html</div>")
    ok(b10.any { it is Block.Html }, "HTML 块")
    val b11 = Md.parse("标题\n====")
    ok(b11[0] is Block.Heading && (b11[0] as Block.Heading).level == 1, "setext 标题")
    val b12 = Md.parse("| a |\n| --- |\n| **b** |")
    ok((b12[0] as Block.Table).rowsInl[0][0].any { it is Inl.Strong }, "表格单元格内加粗")
    val b13 = Md.parse("- 一\n  - 二\n    - 三")
    ok((b13[0] as Block.Bullets).items.map { it.level } == listOf(0, 1, 2), "三级嵌套列表")

    println("== 笔记模型")
    val n = Note(id = "x1", title = "标题", content = "# 内容", createdAt = 1, updatedAt = 2, pinned = true, renderMode = true)
    val back = Note.fromMap(n.toMap())
    ok(back == n, "toMap/fromMap 往返一致")
    ok(Json.str(n.toMap(), "title", "") == "标题", "JSON 序列化字段")

    println("通过 $pass 项，失败 $fail 项")
    if (fail > 0) System.exit(1)
}
