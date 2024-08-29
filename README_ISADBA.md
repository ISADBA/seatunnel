# PostgreSQL双向同步，避免数据回环功能说明

## 背景
为了完成PostgreSQL双向同步，避免数据回环功能的说明，本文档将详细阐述双向同步的原理、配置方法、使用方法、注意事项等。

## 双向同步问题
PostgreSQL双向同步功能是指两个PostgreSQL数据库之间可以互相复制数据，使得数据在两个数据库之间实时同步，从而避免数据回环。
比如A实例 <---> B实例，A实例中的数据发生变更，B实例中的数据也会实时更新。然后B实例中的数据发生变更，A实例中的数据也会实时更新。
这样导致任何一个实例发生变更，数据会在两个实例之间一直循环同步，出现死循环。

## 解决方案
### source端 
source端是PostgreSQL-CDC模块完成日志的拉取与解析，然后传递给计算引擎zeta、flink、spark等进行处理。
在这个场景，我们把`__decycle_table`作为一个flag，如果事务包含`__decycle_table`表的操作，那么认为是数据回环，需要对事务进行过滤。

### sink端
sink端是通过jdbc进行写入目标PostgreSQL数据库。
在这个场景下，需要对通过同步工具同步的数据进行标记，标记方法是在每条数据插入前，增加一个针对`__decycle_table`的操作。

## 配置方法
1. 在要同步的源库和目标库创建标记表
`create table __decycle_table(id bigint,version bigint,primary key(id));`


2. 修改配置文件
```conf
env {
  # You can set engine configuration here
  execution.parallelism = 1
  job.mode = "STREAMING"
  checkpoint.interval = 5000
  read_limit.bytes_per_second=7000000
  read_limit.rows_per_second=400
}

source {
  Postgres-CDC {
    result_table_name = "customers_Postgre_cdc"
    username = "postgres"
    password = "123456"
    database-names = ["t_dts"]
    schema-names = ["public"]
    // decycle相关，需要在同步的表里面加上__decycle_table标记表
    table-names = ["t_dts.public.t1","t_dts.public.__decycle_table"]
    base-url = "jdbc:postgresql://127.0.0.1:5431/t_dts"
    // decycle相关，开启同步回环控制
    enable_de_cycle = true
  }
}

transform {

}

sink {
    jdbc {
        # if you would use json or jsonb type insert please add jdbc url stringtype=unspecified option
        batch_size = 0
        url = "jdbc:postgresql://127.0.0.1:5442/t_dts"
        driver = "org.postgresql.Driver"
        user = "postgres"
        password = "123456"
        generate_sink_sql = true
        # You need to configure both database and table
        database = "t_dts"
        schema = "public"
        // decycle相关，开启同步回环控制
        enable_de_cycle = true
        // decycle相关，关闭自动事务提交
        auto_commit = false
    }
}
```