package io.github.meetingassistant.core;

import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One OCR in flight + two waiting; pixels are bounded, temporary, and never archived. */
public final class ImageWorkQueue implements AutoCloseable {
  public interface Factory {
    ImageUnderstandingProvider create();
  }

  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "meeting-image-jobs");
            t.setDaemon(true);
            return t;
          });
  private final ArrayDeque<Job> waiting = new ArrayDeque<>();
  private final java.util.Set<Job> jobs =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private Job active;
  private boolean closed;

  private static final class Job {
    SessionEngine owner;
    ImageEvidence image;
    byte[] frame;
    Factory factory;
    ProviderConfig config;
    ProviderConfig.Credentials keys;
    ImageUnderstandingProvider provider;
    boolean done;
    java.util.concurrent.ScheduledFuture<?> timeout;
  }

  /** Called after a real frame is copied. False means no success toast and no accepted evidence. */
  public synchronized boolean submit(
      SessionEngine owner,
      ImageEvidence image,
      byte[] jpeg,
      Factory factory,
      ProviderConfig c,
      ProviderConfig.Credentials keys) {
    if (closed || jobs.size() >= 3 || jpeg.length == 0 || jpeg.length > 8 * 1024 * 1024)
      return false;
    Job job = new Job();
    job.owner = owner;
    job.image = image.copy();
    job.frame = jpeg;
    job.factory = factory;
    job.config = c.copy();
    job.keys = keys;
    if (!owner.acceptImage(image, () -> dispatch(() -> cancel(job)))) return false;
    jobs.add(job);
    dispatch(
        () -> {
          synchronized (ImageWorkQueue.this) {
            if (job.done) return;
            waiting.add(job);
          }
          next();
        });
    return true;
  }

  public synchronized boolean full() {
    return closed || jobs.size() >= 3;
  }

  public synchronized boolean busy() {
    return !jobs.isEmpty();
  }

  private void dispatch(Runnable task) {
    try {
      worker.execute(task);
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
    }
  }

  private void next() {
    Job job;
    synchronized (this) {
      if (active != null || closed) return;
      job = waiting.poll();
      if (job == null) return;
      active = job;
    }
    if (job.owner.closed()) {
      cancel(job);
      return;
    }
    if (!job.owner.imageState(job.image.id, ImageEvidence.State.RECOGNIZING, "")) {
      cancel(job);
      return;
    }
    job.timeout = worker.schedule(() -> finish(job, null, "图像识别超时，请重新抓拍"), 180, TimeUnit.SECONDS);
    try {
      Factory factory;
      byte[] pixels;
      synchronized (this) {
        if (job.done || closed) return;
        factory = job.factory;
        pixels = job.frame;
        job.factory = null;
        job.frame = null;
      }
      ImageUnderstandingProvider provider = factory.create();
      synchronized (this) {
        if (job.done || closed) {
          provider.cancel();
          return;
        }
        job.provider = provider;
      }
      provider.recognize(
          pixels,
          new ImageUnderstandingProvider.Listener() {
            public void completed(String result) {
              dispatch(() -> finish(job, result, null));
            }

            public void failed(String message) {
              dispatch(() -> finish(job, null, message));
            }
          });
    } catch (RuntimeException e) {
      finish(job, null, "无法提交图像识别，请检查配置");
    }
  }

  private void finish(Job job, String result, String error) {
    synchronized (this) {
      if (job.done) return;
      job.done = true;
      jobs.remove(job);
      waiting.remove(job);
      if (active == job) active = null;
    }
    if (job.provider != null) job.provider.cancel();
    if (result != null) {
      try {
        job.owner.imageRecognized(job.image.id, result, job.config, job.keys, job.image.preference);
      } catch (RuntimeException e) {
        job.owner.imageState(job.image.id, ImageEvidence.State.ERROR, "图像识别结果无效，请重拍");
      }
    } else job.owner.imageState(job.image.id, ImageEvidence.State.ERROR, error);
    release(job);
    next();
  }

  private void cancel(Job job) {
    synchronized (this) {
      if (job.done) return;
      job.done = true;
      jobs.remove(job);
      waiting.remove(job);
      if (active == job) active = null;
    }
    if (job.provider != null) job.provider.cancel();
    release(job);
    next();
  }

  private void release(Job job) {
    if (job.timeout != null) job.timeout.cancel(false);
    job.frame = null;
    job.factory = null;
    job.keys = null;
    job.config = null;
    job.provider = null;
  }

  public void close() {
    java.util.List<Job> all;
    synchronized (this) {
      closed = true;
      all = new java.util.ArrayList<>(jobs);
    }
    for (Job job : all) cancel(job);
    worker.shutdownNow();
  }
}
