# CPU缓存和内存速度的关系
一个Demo,用来探寻,

1 随机查询内存中数据的速度,是否CPU缓存影响?  结论: 会

2 顺序查询内存中数据的速度,是否CPU缓存影响?  结论: 不会. (CPU做了优化,会预读取到CPU缓存里)

3 数据在内存中存放的位置,是否会影响读取? 比如说放到一个64K的缓存行开头,中间,或者是跨缓存行. 结论: 会

结论:
- 先在堆外固定不同大小的内存,往里面写满数据后, 开始读取. 可以发现,
-- 分配的内存块在0-32K的范围内,随机查询性能基本不变. (对应本机的32K*6的CPU一级缓存)
 -- 在 32K-512K的范围内,随机查询性能逐渐下降. (CPU二级缓存)
 -- 当数据量大部分来自内存的时候,性能几乎不在下降(因为CPU缓存的影响变小了)

其他有意思的结论:

 - 当数据从内存中直接读(分配的内存块过大,CPU缓存不够用了). 一个数据如果是在缓存行的中间,而不是开头,会导致读取速度降低. 
 - 但是如果总数据量足够小,CPU可以全部缓存的时候,输入如果在缓存行中间,反而会加速读取. 应该是CPU在缓存上做的某种优化
 
