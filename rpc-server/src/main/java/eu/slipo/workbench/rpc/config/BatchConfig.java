package eu.slipo.workbench.rpc.config;


import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.task.TaskExecutor;

@Configuration
@PropertySource("classpath:config/batch.properties")
@EnableBatchProcessing
@DependsOn("dataSource")
public class BatchConfig 
{
    @Autowired
    TaskExecutor taskExecutor;
    
    @Autowired
    JobExplorer explorer;
    
    @Autowired
    JobRepository repository;
    
    @Autowired
    JobRegistry registry;
    
    @Bean
    JobLauncher jobLauncher()
    {
        // Setup with our task executor (default is SyncTaskExecutor)
        SimpleJobLauncher launcher = new SimpleJobLauncher();
        launcher.setJobRepository(repository);
        launcher.setTaskExecutor(taskExecutor);
        
        return launcher;
    }
    
    @Bean 
    JobOperator jobOperator(JobLauncher launcher)
    {
        // Setup operator to use our launcher
        SimpleJobOperator operator = new SimpleJobOperator();
        operator.setJobExplorer(explorer);
        operator.setJobLauncher(launcher);
        operator.setJobRegistry(registry);
        operator.setJobRepository(repository);
        
        return operator;
    }
 }