package dev.dsh.mobile.core.model.preset

/** 导入失败的整包原因；中文文案在 UI 层按资源映射。 */
enum class CardError { Blank, BadJson, Shape, UnsupportedSchema, TooManyCards, EmptyCards, MissingName, TooLong, BadType }

/** 单张被跳过的卡：原因 + 出事的字段 + 卡名（可能为空，用于提示）。 */
data class CardSkip(val reason: CardError, val field: String = "", val name: String = "")
