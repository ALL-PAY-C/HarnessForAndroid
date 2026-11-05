package dev.dsh.mobile.core.protocol

// S1: 本体已上移 core:model.port（core:model 不得依赖 core:contract，故反向下沉）。
// core:contract / core:agent 两个模块都在包 dev.dsh.mobile.core.protocol 下，消费方 import 一行不改。
typealias DshTransport = dev.dsh.mobile.core.model.port.DshTransport
