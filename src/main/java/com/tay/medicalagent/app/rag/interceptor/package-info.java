/**
 * RAG 模型调用拦截器。
 * <p>
 * 用于在模型真正调用前将检索上下文拼接到系统提示词，
 * 让 ChatModel 在生成答案时具备外部知识依据。
 */
package com.tay.medicalagent.app.rag.interceptor;
