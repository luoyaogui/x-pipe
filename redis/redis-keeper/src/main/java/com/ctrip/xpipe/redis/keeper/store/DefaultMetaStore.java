/**
 * 
 */
package com.ctrip.xpipe.redis.keeper.store;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unidal.helper.Files.IO;

import com.alibaba.fastjson.JSON;
import com.ctrip.xpipe.endpoint.DefaultEndPoint;
import com.ctrip.xpipe.redis.core.store.MetaStore;
import com.ctrip.xpipe.redis.core.store.ReplicationStore;
import com.ctrip.xpipe.redis.core.store.ReplicationStoreMeta;
import com.ctrip.xpipe.redis.keeper.exception.RedisKeeperRuntimeException;

/**
 * @author marsqing
 *
 *         Jul 26, 2016 11:23:22 AM
 */
public class DefaultMetaStore implements MetaStore {
	
	private static final String META_FILE = "meta.json";

	private static final String ROOT_FILE_PATTERN = "root-%s.json";

	private final static Logger log = LoggerFactory.getLogger(DefaultMetaStore.class);

	private final AtomicReference<ReplicationStoreMeta> metaRef = new AtomicReference<>();

	private File baseDir;

	public DefaultMetaStore(File baseDir) {
		this.baseDir = baseDir;
	}

	@Override
	public String getMasterRunid() {
		return metaRef.get().getMasterRunid();
	}

	@Override
	public Long beginOffset() {
		return metaRef.get().getBeginOffset();
	}

	@Override
	public void setMasterAddress(DefaultEndPoint endpoint) throws IOException {
		synchronized (metaRef) {
			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();

			metaDup.setMasterAddress(endpoint);

			saveMeta(metaDup);
		}
	}

	@Override
	public void updateKeeperRunid(String keeperRunid) throws IOException {
		
		synchronized (metaRef) {

			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();
			if(metaDup.getKeeperRunid() != null && !metaDup.getKeeperRunid().equals(keeperRunid)){
				log.warn("[keeperRunIdChanged]{}->{}", metaDup.getKeeperRunid(), keeperRunid);
			}
			metaDup.setKeeperRunid(keeperRunid);;
			saveMeta(metaDup);
		}
		
	}

	@Override
	public DefaultEndPoint getMasterAddress() {
		return metaRef.get().getMasterAddress();
	}

	@Override
	public long getKeeperBeginOffset() {

		return metaRef.get().getKeeperBeginOffset();
	}

	@Override
	public ReplicationStoreMeta dupReplicationStoreMeta() {
		return new ReplicationStoreMeta(metaRef.get());
	}

	private ReplicationStoreMeta getReplicationStoreMeta(String name) throws IOException {
		File file = new File(baseDir, String.format(ROOT_FILE_PATTERN, name));

		ReplicationStoreMeta meta = null;
		if (file.isFile()) {
			meta = JSON.parseObject(IO.INSTANCE.readFrom(file, "utf-8"), ReplicationStoreMeta.class);
			log.info("[getReplicationStoreMeta]{}, {}, {}", name, meta, file.getAbsolutePath());
			log.info("{}", FileUtils.readFileToString(file));
		}else{
			throw new RedisKeeperRuntimeException("[getReplicationStoreMeta][not file]" + name + ", " + file.getAbsolutePath());
		}

		return meta;
	}

	private void saveMeta(ReplicationStoreMeta newMeta) throws IOException {
		log.info("[Metasaved]\nold:{}\nnew:{}", metaRef.get(), newMeta);
		metaRef.set(newMeta);
		// TODO sync with fs?
		IO.INSTANCE.writeTo(new File(baseDir, META_FILE), JSON.toJSONString(metaRef.get()));
	}

	@Override
	public void loadMeta() throws IOException {
		synchronized (metaRef) {
			File metaFile = new File(baseDir, META_FILE);
			if (metaFile.isFile()) {
				metaRef.set(JSON.parseObject(IO.INSTANCE.readFrom(metaFile, "utf-8"), ReplicationStoreMeta.class));
				ReplicationStoreMeta meta = metaRef.get();

				log.info("Meta loaded: {}", meta);
			} else {
				metaRef.set(new ReplicationStoreMeta());
			}
		}
	}

	@Override
	public void saveMeta(String name, ReplicationStoreMeta replicationStoreMeta) throws IOException {
		// TODO make saveMeta acid
		File file = new File(baseDir, String.format(ROOT_FILE_PATTERN, name));

		IO.INSTANCE.writeTo(file, JSON.toJSONString(replicationStoreMeta));
	}

	@Override
	public void psyncBegun(String masterRunid, long keeperBeginOffset) throws IOException {
		synchronized (metaRef) {
			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();

			metaDup.setMasterRunid(masterRunid);
			metaDup.setKeeperBeginOffset(keeperBeginOffset);

			saveMeta(metaDup);
		}
	}

	@Override
	public ReplicationStoreMeta rdbBegun(String masterRunid, long beginOffset, String rdbFile, long rdbFileSize, String cmdFilePrefix) throws IOException {
		synchronized (metaRef) {
			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();

			metaDup.setMasterRunid(masterRunid);
			metaDup.setBeginOffset(beginOffset);
			metaDup.setRdbFile(rdbFile);
			metaDup.setRdbFileSize(rdbFileSize);
			metaDup.setCmdFilePrefix(cmdFilePrefix);
			metaDup.setRdbLastKeeperOffset(metaDup.getKeeperBeginOffset() - 1);

			saveMeta(metaDup);
			return metaDup;
		}
	}

	@Override
	public void masterChanged(long keeperOffset, DefaultEndPoint newMasterEndpoint, String newMasterRunid, long newMasterReplOffset) throws IOException {
		synchronized (metaRef) {
			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();
			long newBeginOffset = metaDup.getKeeperBeginOffset() + newMasterReplOffset - keeperOffset;

			metaDup.setMasterAddress(newMasterEndpoint);
			metaDup.setMasterRunid(newMasterRunid);
			metaDup.setBeginOffset(newBeginOffset);

			saveMeta(metaDup);

			log.info("[masterChanged]newMasterEndpoint: {},  newMasterRunid: {}, keeperOffset: {}, newMasterReplOffset: {}, newBeginOffset: {}", //
					newMasterEndpoint, newMasterRunid, keeperOffset, newMasterReplOffset, newBeginOffset);
		}

	}

	/**
	 * RedisMaster, ActiveKeeper and BackupKeeper each has its own offset
	 * coordinate(determined by beginOffset and keeperBeginOffset).
	 * 
	 * When Keeper send Psync to RedisMaster or Keeper, it will use begingOffet
	 * + cmdFileSize
	 * 
	 */

	@Override
	public void becomeBackup() throws IOException {
		synchronized (metaRef) {
			log.info("[becomeBackup]");

			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();

			/**
			 * Make the new BackupKeeper can continue cmd from the new
			 * ActiveKeeper which will inherit coordinate from the old
			 * ActiveKeeper(the new BackupKeeper)
			 */
			metaDup.setBeginOffset(metaDup.getKeeperBeginOffset());
			metaDup.setMasterRunid(metaDup.getKeeperRunid());

			saveMeta(metaDup);
		}
	}

	@Override
	public void becomeActive() throws IOException {
		synchronized (metaRef) {
			log.info("[becomeActive]");

			String name = ReplicationStore.BACKUP_REPLICATION_STORE_REDIS_MASTER_META_NAME;
			ReplicationStoreMeta metaOfLastActiveKeeper = getReplicationStoreMeta(name);
			if (metaOfLastActiveKeeper == null) {
				throw new IllegalStateException("can not find meta:" + name);
			}else {
				ReplicationStoreMeta newMeta = dupReplicationStoreMeta();

				/**
				 * Inherit coordinate from last active keeper, so redis slave
				 * and BackupKeeper can continue cmd from this new ActiveKeeper.
				 */
				newMeta.setRdbLastKeeperOffset(metaOfLastActiveKeeper.getRdbLastKeeperOffset()); 
				newMeta.setBeginOffset(metaOfLastActiveKeeper.getBeginOffset());
				newMeta.setKeeperBeginOffset(metaOfLastActiveKeeper.getKeeperBeginOffset());
				newMeta.setKeeperRunid(metaOfLastActiveKeeper.getKeeperRunid());
				newMeta.setMasterAddress(metaOfLastActiveKeeper.getMasterAddress());
				newMeta.setMasterRunid(metaOfLastActiveKeeper.getMasterRunid());

				saveMeta(newMeta);
			} 
		}
	}

	@Override
	public ReplicationStoreMeta rdbUpdated(String rdbFile, long rdbFileSize, long masterOffset) throws IOException {
		synchronized (metaRef) {
			ReplicationStoreMeta metaDup = dupReplicationStoreMeta();

			metaDup.setRdbFile(rdbFile);
			metaDup.setRdbFileSize(rdbFileSize);
			metaDup.setRdbLastKeeperOffset(redisOffsetToKeeperOffset(masterOffset, metaDup));
			log.info("[rdbUpdated] update RdbLastKeeperOffset to {}", metaDup.getRdbLastKeeperOffset());

			saveMeta(metaDup);

			return metaDup;
		}
	}

	/**
	 * keeperOffset - keeperBeginOffset == redisOffset - beginOffset
	 */
	@Override
	public long redisOffsetToKeeperOffset(long redisOffset) {
		return redisOffsetToKeeperOffset(redisOffset, metaRef.get());
	}

	private long redisOffsetToKeeperOffset(long redisOffset, ReplicationStoreMeta meta) {
		
		if(meta.getBeginOffset() == null){
			log.info("[redisOffsetToKeeperOffset][first time create rdb, rdb end set 1]");
			return meta.getKeeperBeginOffset() - 1; 
		}
		return redisOffset - meta.getBeginOffset() + meta.getKeeperBeginOffset();
	}


}