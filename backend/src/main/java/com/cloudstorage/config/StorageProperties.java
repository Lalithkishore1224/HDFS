package com.cloudstorage.config;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where files live and how much of them a drive may hold.
 *
 * @param root          directory containing one sub-directory per storage node
 * @param quotaBytes    maximum total size of all uploaded files
 * @param seedDemoData  whether to fill an empty drive with sample files at start-up
 * @param nodes         number of storage nodes the chunks are spread over
 * @param replicas      number of nodes that hold a copy of each chunk; any (replicas - 1) nodes can fail at once
 * @param nodeUrls      when set, each storage node is a separate server process at one of these addresses
 *                      (see {@code NodeServer}) instead of a directory under {@code root}
 * @param chunkBytes    size of one chunk (HDFS uses 128 MB; small here so demo files span several chunks)
 */
@ConfigurationProperties("storage")
public record StorageProperties(Path root, long quotaBytes, boolean seedDemoData,
                                @DefaultValue("5") int nodes, @DefaultValue("3") int replicas,
                                @DefaultValue("262144") int chunkBytes, List<String> nodeUrls) {
}
