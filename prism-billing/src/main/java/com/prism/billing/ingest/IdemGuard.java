package com.prism.billing.ingest;

/**
 * 用量消息幂等守卫（Redis prism:idem:usage:{msgId}）。
 *
 * 顺序约定（关键，防丢数据）：
 *  - 消费时 seen(msgId) 只读检查（EXISTS），已存在则跳过；
 *  - mark(msgId)（SET NX EX 172800）在批量落库成功之后执行。
 * 若在消费时即 SETNX，一旦落库前进程崩溃，消息重投后会被幂等键误判为已处理而永久丢失；
 * 反过来"先查后置"，崩溃窗口内的重复由 DB uk_msg_id 唯一索引兜底（最后防线），
 * 多实例重复认领（XCLAIM 竞争）同样被唯一索引吸收。
 */
public interface IdemGuard {

    /** 是否已处理过（EXISTS，只读不写） */
    boolean seen(String msgId);

    /** 标记已处理（SET NX EX 48h），落库成功后调用 */
    void mark(String msgId);
}
