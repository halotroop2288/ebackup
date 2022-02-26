package dev.espi.ebackup;

import com.cronutils.descriptor.CronDescriptor;
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;

/*
   Copyright 2020 EspiDev

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

public class CronUtil {

    static Cron cron;
    static ExecutionTime executionTime;
    static ZonedDateTime nextExecution;

    public static void checkCron() {
        CronDefinition cronDefinition = CronDefinitionBuilder.defineCron()
                .withSeconds().and()
                .withMinutes().and()
                .withHours().and()
                .withDayOfMonth()
                    .supportsHash().supportsL().supportsW().and()
                .withMonth().and()
                .withDayOfWeek()
                    .withIntMapping(7, 0) // non-standard non-zero numbers
                    .supportsHash().supportsL().supportsW().and()
                .instance();

        CronParser parser = new CronParser(cronDefinition);
        cron = parser.parse(eBackup.getPlugin().crontask);
        cron.validate();

        eBackup.getPlugin().getLogger().info("Configured the cron task to be: " + CronDescriptor.instance(Locale.UK).describe(cron));

        executionTime = ExecutionTime.forCron(cron);
        Optional<ZonedDateTime> zdt = executionTime.nextExecution(ZonedDateTime.now());
        zdt.ifPresent(next -> nextExecution = next);
    }

    public static boolean run() {
        ZonedDateTime time = ZonedDateTime.now();
        if (nextExecution.isBefore(time)) {
            Optional<ZonedDateTime> zdt = executionTime.nextExecution(time);
            zdt.ifPresent(next -> nextExecution = next);
            return true;
        } else {
            return false;
        }
    }
}
