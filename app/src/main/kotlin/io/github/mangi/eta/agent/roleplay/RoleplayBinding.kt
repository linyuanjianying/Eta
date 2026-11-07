package io.github.mangi.eta.agent.roleplay

import io.github.mangi.eta.data.db.CharacterEntity
import kotlinx.serialization.json.Json

/**
 * 新会话沿用角色的绑定编码。
 *
 * 失败一律返回 null：偏好文件损坏、旧版本字段或角色已被删除都不能让新建会话失败。
 */
internal object RoleplayBindingCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(binding: RoleplayBinding): String = json.encodeToString(binding)

    /** 解析失败按「没有上次角色」处理，不抛异常。 */
    fun decode(raw: String?): RoleplayBinding? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.decodeFromString<RoleplayBinding>(value) }.getOrNull()
            ?.takeIf { it.characterId.isNotBlank() && it.cardSnapshotJson.isNotBlank() }
    }
}

/**
 * 把「上次使用的角色」还原成当前角色目录里的绑定。
 *
 * 角色已被删除或归档时返回 null（安全回退到普通会话）；角色仍存在时以目录里的最新卡片为准，
 * 避免复用过期的角色设定或已失效的头像路径，用户人设仍取上次的值。
 */
internal fun RoleplayBinding.withLatestCharacter(stored: CharacterEntity?): RoleplayBinding? {
    val entity = stored ?: return null
    if (entity.archived) return null
    val card = runCatching { CharacterCardCodec.decodeJson(entity.cardJson) }.getOrNull() ?: return null
    return copy(
        characterId = entity.id,
        cardSnapshotJson = CharacterCardCodec.encodeJson(card),
        characterName = card.name.ifBlank { entity.name },
        avatarPath = entity.avatarPath,
    )
}
