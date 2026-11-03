# ProGuard rules for DeskPet

-keepattributes *Annotation*

# 数据模型类：JSON 序列化存储到 DataStore，序列化器由 kotlinx.serialization 编译期生成。
# 只 keep 我们自己的模型包，不再全量 keep androidx.compose.** / kotlinx.serialization.**
# （kotlinx-serialization 1.6+ 自带 consumer 规则，R8 可正常处理运行时部分）
-keep class com.deskpet.data.model.** { *; }
