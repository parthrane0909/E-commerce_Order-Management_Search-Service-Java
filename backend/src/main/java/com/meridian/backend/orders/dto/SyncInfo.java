package com.meridian.backend.orders.dto;

public class SyncInfo {
    private Boolean enqueued;
    private String taskId;
    private String queue;
    private String error;

    public Boolean getEnqueued() { return enqueued; }
    public void setEnqueued(Boolean enqueued) { this.enqueued = enqueued; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getQueue() { return queue; }
    public void setQueue(String queue) { this.queue = queue; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public static SyncInfoBuilder builder() {
        return new SyncInfoBuilder();
    }

    public static class SyncInfoBuilder {
        private Boolean enqueued;
        private String taskId;
        private String queue;
        private String error;

        public SyncInfoBuilder enqueued(Boolean enqueued) { this.enqueued = enqueued; return this; }
        public SyncInfoBuilder taskId(String taskId) { this.taskId = taskId; return this; }
        public SyncInfoBuilder queue(String queue) { this.queue = queue; return this; }
        public SyncInfoBuilder error(String error) { this.error = error; return this; }
        public SyncInfo build() {
            SyncInfo info = new SyncInfo();
            info.enqueued = enqueued;
            info.taskId = taskId;
            info.queue = queue;
            info.error = error;
            return info;
        }
    }
}