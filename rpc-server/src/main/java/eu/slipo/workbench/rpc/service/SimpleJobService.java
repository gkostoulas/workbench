package eu.slipo.workbench.rpc.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameter.ParameterType;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.configuration.JobFactory;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobInstanceAlreadyExistsException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.step.tasklet.StoppableTasklet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import eu.slipo.workbench.rpc.domain.JobParameterEntity;
import eu.slipo.workbench.rpc.model.MissingJobParameterException;
import eu.slipo.workbench.rpc.repository.JobParameterRepository;

@Service
public class SimpleJobService implements JobService
{
    private static final Logger logger = LoggerFactory.getLogger(SimpleJobService.class);
    
    @Autowired
    private JobExplorer explorer;
    
    @Autowired
    private JobRepository repository;
    
    @Autowired
    private JobRegistry registry;
    
    @Autowired
    private JobLauncher launcher;
    
    @Autowired
    private JobParameterRepository parametersRepository;
    
    @Value("${slipo.rpc-server.job-service.stop-on-shutdown}")
    private boolean stopOnShutdown = false;
    
    @Value("${slipo.rpc-server.job-service.recover-on-init}")
    private boolean recoverOnInit = false;
    
    @Value("${slipo.rpc-server.job-service.ignore-unknown-parameters:false}")
    private boolean ignoreUnknownParameters = false;
    
    /**
     * Initialize before this service bean is available.
     */
    @PostConstruct
    private void initialize()
    {        
        if (recoverOnInit) {
            int countRunning = 0;
            // Reset status for abnormally terminated (interrupted) jobs.
            // This kind of recovery is justified because (assuming a single job service is running!),
            // no job execution should have a running status at this point of time (initialization).
            for (String jobName: explorer.getJobNames()) {
                for (JobExecution execution: findRunningExecutions(jobName)) {
                    // Clear executions that (falsely) appear as running
                    countRunning++;
                    clearRunningExecution(execution, BatchStatus.ABANDONED);
                    logger.debug("Clearing execution {}#{} left as {}", 
                        jobName, execution.getId(), execution.getStatus());
                }
            }
            if (countRunning > 0)
                logger.info("Cleared {} running executions", countRunning);
        }
    }
    
    /**
     * Cleanup before this service bean is destroyed.
     * 
     * <p>
     * The basic duty for this pre-destroy hook is to (attempt to) stop running
     * job executions. Note that configuration must ensure that task executor has "enough" 
     * grace period (awaiting termination timeout), otherwise some steps may not catch-up 
     * and stop  their tasklets. How much is "enough" is determined basically by 2 factors:
     * <ul> 
     *   <li>the maximum time a step spends on processing a chunk of input (since checks 
     *       are performed on chunk boundaries)</li>
     *   <li>the (estimated) number of per-thread load for the task executor</li>
     * </ul>  
     */
    @PreDestroy
    private void cleanup()
    {
        if (stopOnShutdown) {
            // Attempt to stop running executions
            int countRunning = 0, countStopped = 0;
            // Do not count on registry to return job names (may be unregistered on the
            // time we query them): just query repository for names seen so far.
            for (String jobName: explorer.getJobNames()) {
                for (JobExecution execution: findRunningExecutions(jobName)) {
                    BatchStatus currentStatus = execution.getStatus();
                    if (currentStatus != BatchStatus.STOPPING) {
                        stop(execution);
                        logger.debug("Requested from running ({}) execution {}#{} to stop", 
                            currentStatus, jobName, execution.getId());
                        countStopped++;
                    }
                    countRunning++;
                }
            }
            if (countRunning > 0)
                logger.info("Requested from {}/{} running executions to stop", 
                    countStopped, countRunning);
        }
    }
    
    @Override
    public Collection<String> getNames()
    {
        return registry.getJobNames();
    }
    
    @Override
    public JobExecution start(String jobName, JobParameters parameters) throws JobExecutionException
    {   
        Job job = null; 
        try {
            job = registry.getJob(jobName);
        } catch (NoSuchJobException ex) {
            logger.error("No such job: {}", jobName);
            throw ex;
        }
        
        JobExecution x = null;
        try {
            x = launcher.run(job, parameters);
        } catch (JobExecutionException ex) {
            logger.error("Failed to start job: {}", ex.getMessage());
            throw ex;
        }
        
        return x;
    }
    
    @Override
    public void stop(long executionId)
    {
        JobExecution execution = findExecution(executionId);
        if (execution != null) 
            stop(execution);
    }

    /**
     * This method is implemented in a similar (but more simplified) way to 
     * {@link SimpleJobOperator#stop(long)}. A step defined as tasklet extending 
     * {@link StoppableTasklet} will be ignored (no callback is invoked). 
     */
    private void stop(JobExecution execution) 
    {
        Assert.notNull(execution, "The execution cannot be null");
        
        // The execution should be stopped by setting it's status to STOPPING. It is 
        // assumed that the step implementation will check this status at chunk boundaries.
        
        BatchStatus status = execution.getStatus();
        if (!(status == BatchStatus.STARTED || status == BatchStatus.STARTING)) {
            logger.info(
                "The job execution #{} cannot be stopped because is not running ({})",
                execution.getId(), status);
            return;
        } 
        
        execution.setStatus(BatchStatus.STOPPING);
        repository.update(execution);
    }
    
    @Override
    public void stop(String jobName, JobParameters params)
    {
      JobInstance y = findInstance(jobName, params);
      if (y != null) {
          long yid = y.getInstanceId();
          JobExecution x = findRunningExecution(jobName, yid);
          if (x != null) {
              stop(x.getId());
          } else {
              logger.info("No running execution for job instance #{}", yid);
          }
      } else {
          logger.warn("No job instance for name={} parameters={}", jobName, params);
      }
    }
    
    @Override
    public List<JobExecution> findExecutions(String jobName, long instanceId)
    {
        return explorer.getJobExecutions(new JobInstance(instanceId, jobName));
    }

    @Override
    public JobInstance findInstance(String jobName, JobParameters params)
    {
        // Note: The above is not always correct: a JobInstance could have been 
        // created but not started (thus, no executions will be found in repository). 
        // However, if always launching jobs via JobLauncher, a launched Job will 
        // always have at least one associated execution.
        
        JobExecution execution = repository.getLastJobExecution(jobName, params);
        return execution != null? execution.getJobInstance() : null;
    }

    @Override
    public Set<JobExecution> findRunningExecutions(String jobName)
    {
        return explorer.findRunningJobExecutions(jobName);
    }

    @Override
    public JobExecution findRunningExecution(String jobName, long instanceId)
    {
        List<JobExecution> executions = 
            explorer.getJobExecutions(new JobInstance(instanceId, jobName));
            
        for (JobExecution x: executions)
            if (x.isRunning())
                return x;
        return null;
    }

    @Override
    public List<JobInstance> findInstances(String jobName, int start, int count)
    {
        return explorer.findJobInstancesByJobName(jobName, start, count);
    }

    @Override
    public int countInstances(String jobName)
    {
        int n = 0;
        try {
            n = explorer.getJobInstanceCount(jobName);
        } catch (NoSuchJobException e) {
            n = -1;
        }
        return n;
    }

    @Override
    public JobInstance findInstance(long instanceId)
    {
        return explorer.getJobInstance(instanceId);
    }

    @Override
    public JobExecution findExecution(long executionId)
    {
        return explorer.getJobExecution(executionId);
    }
    
    @Override
    public JobExecution clearRunningExecution(String jobName, long instanceId, BatchStatus newStatus)
    {
        JobExecution execution = findRunningExecution(jobName, instanceId);
        if (execution != null)
            return clearRunningExecution(execution, newStatus);
        return null;
    }
    
    private JobExecution clearRunningExecution(JobExecution execution, BatchStatus newStatus)
    {
        Assert.notNull(execution, "The execution cannot be null");
        Assert.isTrue(newStatus == BatchStatus.FAILED ||
                newStatus == BatchStatus.STOPPED ||
                newStatus == BatchStatus.ABANDONED ||
                newStatus == BatchStatus.UNKNOWN, 
            "The new status must correspond to a non-running execution!");
        
        logger.info(
            "About to reset to {} the running execution #{} of instance #{}", 
            newStatus, execution.getId(), execution.getJobInstance().getInstanceId());
        
        // Update record for this job execution to a non-running status
        execution.setStatus(newStatus);
        execution.setExitStatus(ExitStatus.UNKNOWN);
        execution.setEndTime(execution.getCreateTime()); // must have non-null endTime!
        repository.update(execution);
        
        return execution;
    }

    private SpelExpressionParser expressionParser = new SpelExpressionParser();
    
    @Override
    public JobParameters prepareParameters(String jobName, Map<String, Object> providedParameters) 
        throws MissingJobParameterException
    {
        JobParametersBuilder parametersBuilder = new JobParametersBuilder();
        
        // Fetch base parameters (descriptor for expected parameter along with defaults)
        
        List<JobParameterEntity> baseParameters = parametersRepository.findByJobName(jobName);
        Set<String> baseKeys = baseParameters.stream().map(p -> p.getName())
            .collect(Collectors.toSet());
        
        // Handle unknown parameters (i.e. those with no corresponding base parameter)
        
        if (!ignoreUnknownParameters) {
            for (String key: providedParameters.keySet())
                if (!baseKeys.contains(key)) {
                    // Add another parameter: keep basic types if possible (else stringify)
                    Object value = providedParameters.get(key);
                    if (value instanceof Long || value instanceof Integer)
                        parametersBuilder.addLong(key, ((Number) value).longValue());
                    else if (value instanceof Double)
                        parametersBuilder.addDouble(key, ((Double) value).doubleValue());
                    else if (value instanceof Date)
                        parametersBuilder.addDate(key, ((Date) value));
                    else
                        parametersBuilder.addString(key, value.toString());
                }
        }
        
        // Prepare known parameters: evaluate (if needed), cast to their expected type
        
        for (JobParameterEntity baseParameterEntity: baseParameters) {
            String key = baseParameterEntity.getName();
            boolean identifying = baseParameterEntity.isIdentifying();
            // Determine the actual value of this parameter
            Object value = providedParameters.get(key);
            if (value == null) {
                // No value is supplied: populate with default value
                Object defaultValue = baseParameterEntity.getDefaultValue();
                if (defaultValue == null) {
                    String defaultExpression = baseParameterEntity.getDefaultExpression();
                    if (defaultExpression != null) // evaluate as an expression
                        defaultValue = expressionParser.parseExpression(defaultExpression)
                            .getValue();
                }
                if (defaultValue == null) {
                    boolean required = baseParameterEntity.isRequired();
                    if (required)
                        throw new MissingJobParameterException(key);
                    else 
                        continue; // skip this missing parameter
                }
                value = defaultValue;
            }
            // Convert value (if needed) and cast to expected type 
            ParameterType parameterType = baseParameterEntity.getType();
            switch (parameterType) {
            case LONG:
            {
                Long y = null;
                if (value instanceof Number)
                    y = ((Number) value).longValue();
                else
                    y = Long.valueOf(value.toString());
                parametersBuilder.addLong(key, y, identifying);
            }
            break;
            case DOUBLE:
                {
                    Double y = null;
                    if (value instanceof Number)
                        y = ((Number) value).doubleValue();
                    else 
                        y = Double.valueOf(value.toString());
                    parametersBuilder.addDouble(key, y, identifying);
                }
                break;
            case DATE:
                {
                    Date y = null;
                    if (value instanceof Date)
                        y = (Date) value;
                    else if (value instanceof ZonedDateTime)
                        y = Date.from(((ZonedDateTime) value).toInstant());
                    else if (value instanceof Long) // treat as milliseconds since Epoch 
                        y = new Date(((Long) value).longValue());
                    else // treat as an ISO-8601 formatted string
                        y = Date.from(ZonedDateTime.parse(value.toString()).toInstant());
                    parametersBuilder.addDate(key, y, identifying);
                }
                break;
            case STRING:
                parametersBuilder.addString(key, value.toString(), identifying);
                break;
            }
        }
        
        // Build
        
        return parametersBuilder.toJobParameters();
    }
}