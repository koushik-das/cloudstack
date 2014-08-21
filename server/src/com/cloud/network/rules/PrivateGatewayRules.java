// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.network.rules;

import org.apache.cloudstack.network.topology.NetworkTopologyVisitor;
import org.apache.log4j.Logger;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.PrivateIpVO;
import com.cloud.vm.NicProfile;

public class PrivateGatewayRules extends RuleApplier {

    private static final Logger s_logger = Logger.getLogger(PrivateGatewayRules.class);

    private final PrivateGateway _privateGateway;

    private boolean _isAddOperation;
    private NicProfile _nicProfile;

    public PrivateGatewayRules(final PrivateGateway privateGateway) {
        super(null);
        _privateGateway = privateGateway;
    }

    @Override
    public boolean accept(final NetworkTopologyVisitor visitor, final VirtualRouter router) throws ResourceUnavailableException {
        _router = router;

        boolean result = false;
        try {
            _network = _networkModel.getNetwork(_privateGateway.getNetworkId());
            NicProfile requested = _vpcNetworkHelper.createPrivateNicProfileForGateway(_privateGateway);

            if (!_networkHelper.checkRouterVersion(router)) {
                s_logger.warn("Router requires upgrade. Unable to send command to router: " + router.getId());
                return false;
            }
            _nicProfile = _itMgr.addVmToNetwork(router, _network, requested);

            //setup source nat
            if (_nicProfile != null) {
                _isAddOperation = true;
                //result = setupVpcPrivateNetwork(router, true, guestNic);
                result = visitor.visit(this);
            }
        } catch (Exception ex) {
            s_logger.warn("Failed to create private gateway " + _privateGateway + " on router " + router + " due to ", ex);
        } finally {
            if (!result) {
                s_logger.debug("Failed to setup gateway " + _privateGateway + " on router " + router + " with the source nat. Will now remove the gateway.");
                _isAddOperation = false;
                boolean isRemoved = destroyPrivateGateway(visitor);

                if (isRemoved) {
                    s_logger.debug("Removed the gateway " + _privateGateway + " from router " + router + " as a part of cleanup");
                } else {
                    s_logger.warn("Failed to remove the gateway " + _privateGateway + " from router " + router + " as a part of cleanup");
                }
            }
        }
        return result;
    }

    public boolean isAddOperation() {
        return _isAddOperation;
    }

    public NicProfile getNicProfile() {
        return _nicProfile;
    }

    public PrivateIpVO retrivePrivateIP() {
        PrivateIpVO ipVO = _privateIpDao.findByIpAndSourceNetworkId(_nicProfile.getNetworkId(), _nicProfile.getIp4Address());
        return ipVO;
    }

    public Network retrievePrivateNetwork() {
        // This network might be the same we have already as an instance in the RuleApplier super class.
        // Just doing this here, but will double check is remove if it's not needed.
        Network network = _networkDao.findById(_nicProfile.getNetworkId());
        return network;
    }

    protected boolean destroyPrivateGateway(final NetworkTopologyVisitor visitor) throws ConcurrentOperationException, ResourceUnavailableException {

        if (!_networkModel.isVmPartOfNetwork(_router.getId(), _privateGateway.getNetworkId())) {
            s_logger.debug("Router doesn't have nic for gateway " + _privateGateway + " so no need to removed it");
            return true;
        }

        Network privateNetwork = _networkModel.getNetwork(_privateGateway.getNetworkId());

        s_logger.debug("Releasing private ip for gateway " + _privateGateway + " from " + _router);

        _nicProfile = _networkModel.getNicProfile(_router, privateNetwork.getId(), null);
        boolean result = visitor.visit(this);
        if (!result) {
            s_logger.warn("Failed to release private ip for gateway " + _privateGateway + " on router " + _router);
            return false;
        }

        //revoke network acl on the private gateway.
        if (!_networkACLMgr.revokeACLItemsForPrivateGw(_privateGateway)) {
            s_logger.debug("Failed to delete network acl items on " + _privateGateway + " from router " + _router);
            return false;
        }

        s_logger.debug("Removing router " + _router + " from private network " + privateNetwork + " as a part of delete private gateway");
        result = result && _itMgr.removeVmFromNetwork(_router, privateNetwork, null);
        s_logger.debug("Private gateawy " + _privateGateway + " is removed from router " + _router);
        return result;
    }
}