package dev.dsh.mobile.feature.sessions

/**
 * 长按 → 抬手 click 的一次性消费标志。
 *
 * 长按进入多选后，行内的 clickable 在抬手时仍会触发一次 onClick。不能用「距长按的
 * 时间窗」判断这次 click 是否属于刚才的长按：手指按住超过窗口再松手时，click 会被
 * 误当成一次新点击，把刚选中的会话又取消掉（M19）。
 *
 * 用法：长按触发时 [arm]；click 回调里先 [consume]，返回 true 表示本次 click 应被
 * 吞掉。[consume] 只生效一次，之后恢复正常点击，与按住时长无关。
 *
 * [disarm] 是 R1 的补丁：arm 之后那次抬手不一定会变成 onClick（手指慢速滑出行边界时
 * clickable 收到 cancel，detectTapGestures 直接返回 null），此时 arm 会一直挂着，把该行
 * **下一次**正常单击静默吞掉。所以在每个新手势开始时调用 [disarm] 作废未兑现的 arm。
 */
internal class ClickSwallow {
    private var armed = false

    /** 长按触发时调用：吞掉紧接着的一次 click。 */
    fun arm() {
        armed = true
    }

    /** click 到达时调用；返回 true 表示本次 click 应被吞掉（一次性）。 */
    fun consume(): Boolean {
        if (!armed) return false
        armed = false
        return true
    }

    /**
     * 新手势（ACTION_DOWN）开始时调用：作废上一轮尚未兑现的 [arm]。
     *
     * 上一次 arm 若已经等到了 click，[consume] 早已把它清掉，这里是无操作；
     * 若那次抬手变成了 cancel，这里的清理保证下一次单击不会被误吞。
     */
    fun disarm() {
        armed = false
    }
}
