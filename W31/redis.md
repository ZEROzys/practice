## redis

### 数据结构和内部编码

内部编码：

每种数据结构都有自己底层的内部编码实现，而且是多种实现，这样设计有两种好处：

- 可以改进内部编码，而对外的数据结构和命令没有影响
- 多种内部编码实现可以在不同场景下发挥各自的优势

5种数据结构：

#### 字符串（string）

值的类型包括简单字符串、复杂字符串（JSON、XML）、数字（整数、浮点数）、二进制（图片、音视频）等，但不能超过512M。

##### 重要命令

**setnx**（键必须不存在才可以设置成功，用于添加）

如果有多个客户端同时执行`setnx key value`，根据setnx的特性只有一个客户端能设置成功，setnx可以作为分布式锁的一种实现方案

**setxx**（键必须存在才可以设置成功，用于更新）

**mset key value [key value ...]** 批量设置值

**mget key [key ...]** 批量获取值

##### 内部编码

int：8个字节的长整型

embstr：<=39个字节的字符串

raw：>39个字节的字符串

#### 哈希（hash）

内部编码

ziplist（元素个数小于配置（默认512个），同时所有值都小于配置（64字节））

hashtable（不满足ziplist的条件时）

#### 列表（list）



### 单线程架构

Redis使用了单线程架构和I/O多路复用模型来实现高性能的内存数据库服务

##### 为什么单线程能这么快

+ 纯内存访问，redis将所有数据放在内存中，内存响应时间非常短，这是redis快的重要基础
+ 非阻塞I/O，redis使用epoll作为I/O多路复用技术的实现，再加上redis自身的事件处理模型将epoll中的连接、读写、关闭都转换为事件
+ 单线程避免线程切换和竞态产生的消耗

### 持久化

#### RDB

RDB持久化是把当前进程数据生成快照保存到硬盘的过程，触发RDB持久化过程分为手动触发和自动触发。

##### 手动触发

bgsave 命令：redis进程执行fork操作创建子进程，RDB持久化过程由子进程负责，完成后自动结束。阻塞只发生在fork阶段，一般时间很短。

##### 自动触发

#### AOF

以独立日志的方式记录每次写命令，重启时再重新执行AOF文件中的命令达到恢复数据的目的，解决了数据持久化的实时性问题。

AOF工作流程如下：

+ 命令写入：所有写入命令会追加到aof_buf中
+ 文件同步：AOF缓冲区根据对应策略向硬盘做同步操作
+ 文件重写：定期对AOF文件重写，达到压缩的目的
+ 重启加载：当redis重启时，可以加载AOF文件进行数据恢复

### 主从复制

#### 作用：

+ 作为主节点的备份，一旦主节点出现问题，从节点可以作为备用，保证数据尽量不丢失（主从复制是最终一致性）
+ 从节点可以扩展主节点的读能力，一旦主节点不能支撑住大并发量的读操作，从节点可以帮助主节点分担读压力

#### 问题

高可用问题和分布式问题

+ 一旦主节点出现故障，需要手动将一个从节点升为主节点，同时需要修改应用方的主节点地址，还需要命令其他节点复制新的主节点，整个过程都需要人工干预
+ 主节点的写能力受到单机的限制
+ 主节点的存储能力受单机的限制

### 哨兵（redis sentinel）

#### 实现原理

##### 三个定时任务

+ 每隔10秒，每个sentinel节点会向主节点和从节点发送info命令获取最新的拓扑结构
+ 每隔2秒，每个sentinel节点向redis数据节点的\_sentinel_:hello频道发送该sentinel节点对于主节点的判断以及当前sentinel节点的信息，同时每个sentinel节点也会订阅该频道，来了解其他sentinel节点以及它们对主节点的判断。
+ 每隔1秒，每个Sentinel节点会向主节点、从节点、其余Sentinel节点发送一条ping命令做一次心跳检测，来确认这些节点当前是否可达。

##### 主观下线和客观下线

每个Sentinel节点会每隔1秒对主节点、从节点、其他Sentinel节点发送ping命令做心跳检测，当这些节点超过down-after-milliseconds没有进行有效回复，Sentinel节点就会对该节点做失败判定，这个行为叫做主观下线。

当Sentinel主观下线的节点是主节点时，该Sentinel节点会通过sentinel ismaster-down-by-addr命令向其他Sentinel节点询问对主节点的判断，当超过个数，Sentinel节点认为主节点确实有问题，这时该Sentinel节点会做出客观下线的决定。

##### 领导者选举（选举的是sentinel节点）

Raft算法

##### 故障转移

### 集群（redis cluster）

#### 数据分布理论

+ 哈希分区
  + 节点取余分区
  + 一致性哈希分区
  + 虚拟槽分区
+ 顺序分区



### 最佳实践

#### 慢查询

`slowlog-max-len` 配置建议：线上建议调大慢查询列表，记录慢查询时redis会对长命令做截断操作，并不会占用大量内存。增大慢查询列表可以减缓慢查询被剔除的可能

`slowlog-log-slower-than` 配置建议：默认值超过10ms判定为慢查询，需要根据redis的并发量调整该值，对于高OPS场景的redis建议设置为1ms。

慢查询只记录命令执行时间，并不包括命令排队和网络传输时间。因此客户端执行命令的时间会大于命令实际执行时间。因为命令执行排队机制，慢查询会导致其他命令级联阻塞，因此当客户端出现请求超时，需要检查该时间点是否有对应的慢查询，从而分析出是否为慢查询导致的命令级联阻塞。

由于慢查询日志是一个先进先出的队列，也就是说如果慢查询比较多的情况下，可能会丢失部分慢查询命令，为了防止这种情况发生，可以定期 执行slow get命令将慢查询日志持久化到其他存储中（例如MySQL）

#### 设计合理键名

+ 业务名:对象名:{id}:[属性] ：”vs:user:1","vs:user:1:name"等等

+ 在能清楚表述键含义的前提下适当减少键的长度

#### 添加过期时间



