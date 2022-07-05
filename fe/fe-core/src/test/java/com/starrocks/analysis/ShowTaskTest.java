// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.analysis;

import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.qe.ConnectContext;
import com.starrocks.scheduler.Constants;
import com.starrocks.scheduler.Task;
import com.starrocks.scheduler.TaskBuilder;
import com.starrocks.scheduler.TaskManager;
import com.starrocks.scheduler.persist.TaskSchedule;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.service.FrontendServiceImpl;
import com.starrocks.sql.ast.SubmitTaskStmt;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.thrift.TGetTaskInfoResult;
import com.starrocks.thrift.TGetTasksParams;
import com.starrocks.thrift.TTaskInfo;
import com.starrocks.thrift.TUserIdentity;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ShowTaskTest {

    private static ConnectContext connectContext;
    private static StarRocksAssert starRocksAssert;

    @BeforeClass
    public static void beforeClass() throws Exception {
        FeConstants.runningUnitTest = true;
        Config.enable_experimental_mv = true;
        UtFrameUtils.createMinStarRocksCluster();

        connectContext = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(connectContext);

        starRocksAssert.withDatabase("test").useDatabase("test")
                .withTable("CREATE TABLE test.tbl1\n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int sum\n" +
                        ")\n" +
                        "PARTITION BY RANGE(k1)\n" +
                        "(\n" +
                        "    PARTITION p1 values less than('2020-02-01'),\n" +
                        "    PARTITION p2 values less than('2020-03-01')\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
    }

    @Test
    public void testShowTasks() throws Exception {
        FrontendServiceImpl frontendService = new FrontendServiceImpl(null);
        TaskManager taskManager = GlobalStateMgr.getCurrentState().getTaskManager();
        connectContext.setExecutionId(UUIDUtil.toTUniqueId(UUIDUtil.genUUID()));

        String submitSQL = "submit task as create table temp as select count(*) as cnt from tbl1";
        SubmitTaskStmt submitTaskStmt = (SubmitTaskStmt) UtFrameUtils.parseStmtWithNewParser(submitSQL, connectContext);
        Task manualTask = TaskBuilder.buildTask(submitTaskStmt, connectContext);
        taskManager.createTask(manualTask, true);

        Task periodTask = new Task();
        periodTask.setName("test_periodical");
        periodTask.setCreateTime(System.currentTimeMillis());
        periodTask.setDbName("test");
        periodTask.setDefinition("select 1");
        periodTask.setExpireTime(0L);
        long startTime = Utils.getLongFromDateTime(LocalDateTime.of(2020, 04, 21, 0, 0, 0));
        TaskSchedule taskSchedule = new TaskSchedule(startTime, 5, TimeUnit.SECONDS);
        periodTask.setSchedule(taskSchedule);
        periodTask.setType(Constants.TaskType.PERIODICAL);
        taskManager.createTask(periodTask, true);

        UserIdentity currentUserIdentity = connectContext.getCurrentUserIdentity();
        TGetTasksParams tGetTasksParams = new TGetTasksParams();
        tGetTasksParams.setCurrent_user_ident(new TUserIdentity(currentUserIdentity.toThrift()));
        TGetTaskInfoResult taskResult = frontendService.getTasks(tGetTasksParams);
        List<TTaskInfo> tasks = taskResult.getTasks();
        Assert.assertEquals(tasks.size() , 2);
        for (TTaskInfo task : tasks) {
            if(task.getTask_name().equals("test_periodical")) {
                Assert.assertEquals(task.getSchedule(),"PERIODICAL (START 2020-04-21T00:00 EVERY(5 SECONDS))");
            } else {
                Assert.assertEquals(task.getSchedule(),"MANUAL");
            }
        }
    }
}