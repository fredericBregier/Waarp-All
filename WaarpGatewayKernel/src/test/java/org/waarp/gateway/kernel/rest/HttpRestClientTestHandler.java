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

package org.waarp.gateway.kernel.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.waarp.common.logging.WaarpLogger;
import org.waarp.common.logging.WaarpLoggerFactory;
import org.waarp.gateway.kernel.rest.client.HttpRestClientSimpleResponseHandler;
import org.waarp.gateway.kernel.rest.client.RestFuture;

public class HttpRestClientTestHandler
    extends HttpRestClientSimpleResponseHandler {
  private static final WaarpLogger logger =
      WaarpLoggerFactory.getLogger(HttpRestClientTestHandler.class);

  protected void actionFromResponse(Channel channel) {
    final RestArgument ra = new RestArgument((ObjectNode) jsonObject);
    logger.debug("RA: {}", ra);
    if (jsonObject == null) {
      logger.warn("Recv: EMPTY");
    } else {
      logger.warn(ra.prettyPrint());
    }
    final RestFuture restFuture = channel.attr(RESTARGUMENT).get();
    restFuture.setRestArgument(ra);
    if (ra.getStatusCode() == HttpResponseStatus.OK.code()) {
      restFuture.setSuccess();
    } else {
      logger.error("Empty: " + ra.getStatusMessage());
      restFuture.cancel();
    }
  }
}
