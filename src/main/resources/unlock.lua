-- 这里的KEYS[1]就是锁的key，ARGV[1]就是线程标识
-- 比较线程标识和锁中的标识是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 释放锁
    return redis.call('del', KEYS[1])
end
return 0