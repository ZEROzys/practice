package zys.practice.seckillcommon.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zys.practice.seckillcommon.pojo.Order;
import zys.practice.seckillcommon.pojo.Product;
import zys.practice.seckillcommon.common.RedisFix;

import java.util.Arrays;
import java.util.Set;

@Service
public class RedisService {

    private StringRedisTemplate redisTemplate;
    private OrderService orderService;
    private ProductService productService;

    @Autowired
    public RedisService(StringRedisTemplate redisTemplate, OrderService orderService,
                        ProductService productService) {
        this.redisTemplate = redisTemplate;
        this.orderService = orderService;
        this.productService = productService;
    }

    public boolean hasKey(String key) {
        if (key == null) return false;
        return redisTemplate.hasKey(key);
    }

    public String get(String key) {
        ValueOperations<String, String > vo =  redisTemplate.opsForValue();
        return vo.get(key);
    }
    public void set(String key, String value) {
        ValueOperations<String, String> vo = redisTemplate.opsForValue();
        vo.set(key, value);
    }
    /***
     * redis 秒杀功能
     */
    public boolean secKill(String productId, String username) {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seckill.lua")));
        redisScript.setResultType(String.class);
        String res = redisTemplate.execute(redisScript, Arrays.asList(productId), username);
        return res.equals("1");
    }

    public Set<String> getAllKeys() {
        Set<String> keys = redisTemplate.keys("*");
        return keys;
    }

    /***
     * 数据库保存订单失败，redis进行回滚操作，product库存量加1，删除对应list中的用户
     */
    public void rollback(String productId, String username) {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rollback.lua")));
        redisScript.setResultType(String.class);
        redisTemplate.execute(redisScript, Arrays.asList(productId), username);
    }

    @Transactional(rollbackFor = Exception.class)
    public void transData2Db() throws Exception{
        for (String key: getAllKeys()) {
            if (key.startsWith(RedisFix.PRODUCT_PREFIX)) {
                String[] strings = key.split(":");
                if (strings.length == 2) {
                    int stock = Integer.valueOf(getVal(key));
                    Product p = productService.getProductById(Long.valueOf(strings[1]));
                    p.setStock(stock);
                    if (productService.save(p) == null)
                        throw new Exception("更新库存失败");
                } else {
                    Order order = new Order(Long.valueOf(strings[1]), Long.valueOf(strings[3]));
                    if (orderService.save(order) == null) {
                        throw new Exception("保存订单错误");
                    }
                }
                if (!delKey(key))
                    throw new Exception("删除键值出现错误");
            }
        }
    }

    public boolean delKey(String key) {
        if (key == null) return false;
        else
            return redisTemplate.delete(key);

    }

    public String getVal(String key) {
        ValueOperations<String, String> opsForValue = redisTemplate.opsForValue();
        return opsForValue.get(key);
    }
}
