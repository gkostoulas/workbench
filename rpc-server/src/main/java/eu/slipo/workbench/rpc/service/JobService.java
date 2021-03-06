package eu.slipo.workbench.rpc.service;

import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.NoSuchJobException;

import eu.slipo.workbench.rpc.model.MissingJobParameterException;

/**
 * A facade interface to interact with Spring Batch jobs.
 */
public interface JobService
{
    /**
     * List names of registered jobs.
     */
    Collection<String> getNames();
    
    /**
     * Start (or restart) a new job by name, passing a map of parameters.
     * <p>
     * If a {@link JobInstance} with the same identifying parameters exists, and
     * is failed, then it will be restarted (if complete, an exception is raised). 
     * If no matching {@link JobInstance} is found, then a new will be created and started.
     * 
     * @param jobName
     * @param params
     * @return the started job execution
     * @throws JobExecutionException if the job could not be started (or restarted).
     */
    JobExecution start(String jobName, JobParameters params) 
        throws JobExecutionException;
    
    /**
     * Stop a running job execution ({@link JobExecution}). If given execution does
     * not represent a running execution, it will do nothing.
     * 
     * @param executionId the id of target job execution
     * @return the updated {@link JobExecution} object
     */
    void stop(long executionId);
    
    /**
     * Stop a running job execution ({@link JobExecution}). The target execution is
     * found as the running execution of a job instance ({@link JobInstance}) identified
     * by job-name and parameters.
     * 
     * @param jobName
     * @param params
     */
    void stop(String jobName, JobParameters params);
    
    /**
     * Find a {@link JobInstance} identified by its instance id. 
     * If nothing is found, <tt>null</tt> is returned. 
     * 
     * @param instanceId
     */
    JobInstance findInstance(long instanceId);
    
    /**
     * Find a {@link JobInstance} identified by a job name and a map of parameters. 
     * If nothing is found, <tt>null</tt> is returned.
     * 
     * @param jobName
     * @param params
     */
    JobInstance findInstance(String jobName, JobParameters params); 
    
    /**
     * Find list of {@link JobInstance} for a given job. The number of instances can be quite large,
     * so you should only fetch them in pages. 
     * 
     * @param jobName
     * @param start The index to start returning from
     * @param count The page size
     */
    List<JobInstance> findInstances(String jobName, int start, int count);
    
    /**
     * Fetch entire list of {@link JobInstance} for a given job.
     * 
     * @param jobName
     */
    default List<JobInstance> findInstances(String jobName)
    {
        int n = countInstances(jobName);
        return n > 0? findInstances(jobName, 0, n) : Collections.emptyList();
    }
    
    /**
     * Count the number of created job instances ({@link JobInstance}) for a given job.
     * 
     * @param jobName
     * @return the actual count, or -1 if no job with given name exists.
     */
    int countInstances(String jobName);
    
    /**
     * Find a {@link JobExecution} identified by its execution id.
     * If nothing is found, <tt>null</tt> is returned. 
     * 
     * @param executionId
     */
    JobExecution findExecution(long executionId);
    
    /**
     * Find list of {@link JobExecution} for a given job instance ({@link JobInstance}) 
     * which is identified by (jobName, instanceId).
     * 
     * <p>
     * A {@link JobInstance} may be associated with more than one executions (i.e {@link JobExecution})
     * if failed and restarted.
     * 
     * @param jobName
     * @param instanceId
     * @return
     * 
     * @see http://docs.spring.io/spring-batch/reference/html/domain.html
     */
    List<JobExecution> findExecutions(String jobName, long instanceId);
    
    /**
     * Find list of {@link JobExecution} for a job instance identified by (jobName, params).
     * 
     * @param jobName
     * @param params
     * @return
     * 
     * @see http://docs.spring.io/spring-batch/reference/html/domain.html
     */
    default List<JobExecution> findExecutions(String jobName, JobParameters params)
    {
        JobInstance y = findInstance(jobName, params);
        return y != null? 
            findExecutions(jobName, y.getInstanceId()): Collections.emptyList();    
    }
   
    /**
     * Find set of running executions ({@link JobExecution}) for a given job. For each job
     * instance there will be at most one running execution.
     * 
     * @param jobName
     * @return
     */
    Set<JobExecution> findRunningExecutions(String jobName);
    
    /**
     * Find running execution ({@link JobExecution}) for a given job instance ({@link JobInstance}). 
     * At any time, at most one running execution exists per job instance.
     * 
     * @param jobName
     * @param instanceId
     * @return a {@link JobExecution} or <tt>null</tt> if no running execution exists
     */
    JobExecution findRunningExecution(String jobName, long instanceId);
    
    /**
     * Reset a running execution of a given job instance to a non-running status.
     * 
     * <p>
     * Note: This method does not attempt to stop a running task: only resets its status.
     * Its purpose is to clear the status of an interrupted task (e.g. by the application
     * shutdown) that falsely appears as {@link BatchStatus#STARTED} but in fact is stopped 
     * in an unknown state. This recovery step is needed because Spring Batch will refuse 
     * to restart the same job instance thinking it already has an active execution.  
     * 
     * @param jobName
     * @param instanceId
     * @param newStatus the new status that a running execution should be set to (must 
     *   be a non-running status)
     *   
     * @return a {@link JobExecution} instance if cleared, <tt>null</tt> otherwise
     */
    JobExecution clearRunningExecution(String jobName, long instanceId, BatchStatus newStatus);
    
    default JobExecution clearRunningExecution(String jobName, long instanceId) 
    {
        return clearRunningExecution(jobName, instanceId, BatchStatus.ABANDONED);
    }
    
    /**
     * Prepare job parameters for a given job: load defaults, merge runtime parameters into defaults, 
     * convert to proper data types (if needed).
     * <p>
     * If a parameter acquires its default value by evaluating an expression, this evaluation should
     * take place here.
     * 
     * @param providedParameters A map of runtime parameters
     * @param jobName The job name
     * 
     * @throws MissingJobParameterException
     * @throws NumberFormatException
     * @throws DateTimeParseException
     * 
     * @return a new {@link JobParameters} instance
     */
    JobParameters prepareParameters(String jobName, Map<String,Object> providedParameters)
        throws MissingJobParameterException;
    
    default JobParameters prepareParameters(String jobName)
        throws MissingJobParameterException
    {
        return prepareParameters(jobName, Collections.emptyMap());
    }
}