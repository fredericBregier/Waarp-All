/*
 * This file is part of Waarp Project (named also Waarp or GG).
 *
 *  Copyright (c) 2019, Waarp SAS, and individual contributors by the @author
 *  tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 *  All Waarp Project is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 * Waarp . If not, see <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.context.task;

import org.waarp.openr66.context.R66Session;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNetworkException;
import org.waarp.openr66.s3.taskfactory.S3TaskFactory;
import org.waarp.openr66.s3.taskfactory.S3TaskFactory.S3TaskType;

/**
 * S3 PUT and DELETE Task<br>
 * <p>
 * Result of arguments will be as a S3 PUT and R66 DELETE command.<br>
 * Format is the following:<br>
 * "-URL url of S3 service <br>
 * -accessKey access Key for S3 service <br>
 * -secretKey secret Key for S3 service <br>
 * -bucketName bucket Name where to retrieve the object <br>
 * -targetName source Name from the bucket to select the final Object <br>
 * [-setTags 'name:value,...' without space]" <br>
 * <br>
 * <br>
 * The order of actions will be:<br>
 * 1) connection to S3 service using access Key and Secret Key<br>
 * 2) Store to the bucket the current File as the target Object<br>
 * 3) if setTags is set, the informations are added as tags to the final S3 Object<br>
 * 4) the current File is deleted<br>
 */
public class S3PutAndR66DeleteTask extends S3PutTask {
  private static final S3TaskFactory.S3TaskType taskType =
      S3TaskFactory.S3TaskType.S3PUTR66DELETE;

  /**
   * Constructor
   *
   * @param argRule
   * @param delay
   * @param argTransfer
   * @param session
   */
  public S3PutAndR66DeleteTask(final String argRule, final int delay,
                               final String argTransfer,
                               final R66Session session) {
    super(argRule, delay, argTransfer, session);
  }

  /**
   * The order of actions will be:<br>
   * 1) connection to S3 service using access Key and Secret Key<br>
   * 2) Store to the bucket the current File as the target Object<br>
   * 3) if setTags is set, the informations are added as tags to the final S3 Object<br>
   * 4) the current File is deleted<br>
   */
  @Override
  public void run() {
    try {
      internalRun();
    } catch (final OpenR66ProtocolNetworkException e) {
      finalizeInError(e, "Error while S3 Action");
      return;
    }
    final DeleteTask deleteTask = new DeleteTask("", 0, "", session);
    deleteTask.run();
    if (deleteTask.futureCompletion.isSuccess()) {
      logger.debug("R66 Deleted");
      futureCompletion.setSuccess();
    } else {
      logger.error("PUT file OK but local file deletion in error");
      futureCompletion.setResult(deleteTask.futureCompletion.getResult());
      futureCompletion.setFailure(deleteTask.futureCompletion.getCause());
    }
  }

  @Override
  public S3TaskType getS3TaskType() {
    return taskType;
  }
}
