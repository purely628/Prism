package com.prism.kb.mq;

/**
 * kb 解析任务派发抽象（契约 §5 双实现）。
 * 消息体 {"docId":x,"kbId":y}——Python 消费端要 kbId 才能回查切分参数。
 */
public interface KbTaskDispatcher {

    void dispatch(long kbId, long docId);
}
