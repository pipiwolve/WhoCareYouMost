/**
 * 应用层门面与跨模块编排入口。
 * <p>
 * 该包向上提供统一的 {@code MedicalApp} 调用入口，向下组合聊天、报告、用户画像、
 * 对话线程与 RAG 等子模块，屏蔽底层 Agent Runtime 的装配细节。
 */
package com.tay.medicalagent.app;
