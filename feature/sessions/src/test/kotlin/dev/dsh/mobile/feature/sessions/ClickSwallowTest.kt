package dev.dsh.mobile.feature.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M19 回归：长按后的抬手 click 必须被一次性吞掉，与按住时长无关。
 *
 * 旧实现用「距 longPressAt 的时间窗」判断是否吞 click，按住超过 700ms 再松手时
 * 窗口已过期 → 二次 toggle 取消选中。ClickSwallow 用状态标志替代时间比较，因此
 * 吞掉行为只取决于「有没有发生过长按」，与按住多久无关。
 */
class ClickSwallowTest {

    @Test
    fun consumeWithoutArmReturnsFalse() {
        val s = ClickSwallow()
        assertFalse("未长按就 click 不应被吞", s.consume())
    }

    @Test
    fun armThenConsumeReturnsTrueExactlyOnce() {
        val s = ClickSwallow()
        s.arm()
        assertTrue("长按后的第一次 click 应被吞", s.consume())
        assertFalse("吞掉是一次性的，第二次 click 应恢复", s.consume())
    }

    @Test
    fun repeatedArmStillConsumesOnlyOnce() {
        val s = ClickSwallow()
        s.arm()
        s.arm()
        assertTrue(s.consume())
        assertFalse(s.consume())
    }

    /**
     * R1 回归：arm 之后那次抬手若没变成 onClick（手指滑出行边界 → clickable 收到 cancel，
     * detectTapGestures 返回 null），arm 会一直挂着，把该行**下一次**正常单击静默吞掉。
     * 新手势开始时必须作废上一轮未兑现的 arm。
     */
    @Test
    fun disarmCancelsPendingArm() {
        val s = ClickSwallow()
        s.arm()
        s.disarm()
        assertFalse("上一轮 arm 未兑现，新手势开始后不应再吞点击", s.consume())
    }

    @Test
    fun disarmOnCleanStateIsHarmless() {
        val s = ClickSwallow()
        s.disarm()
        assertFalse(s.consume())
    }

    /** 正常路径不受 disarm 影响：disarm 之后再 arm，仍应吞掉紧接着的那一次。 */
    @Test
    fun disarmThenArmStillConsumes() {
        val s = ClickSwallow()
        s.disarm()
        s.arm()
        assertTrue(s.consume())
        assertFalse(s.consume())
    }
}
