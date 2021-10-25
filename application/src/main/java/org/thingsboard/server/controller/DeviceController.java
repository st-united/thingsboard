/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.thingsboard.rule.engine.api.msg.DeviceCredentialsUpdateNotificationMsg;
import org.thingsboard.rule.engine.api.msg.DeviceEdgeUpdateMsg;
import org.thingsboard.server.common.data.ClaimRequest;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceInfo;
import org.thingsboard.server.common.data.EntitySubtype;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.SaveDeviceWithCredentialsRequest;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.device.DeviceSearchQuery;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.ota.OtaPackageType;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.dao.device.claim.ClaimResponse;
import org.thingsboard.server.dao.device.claim.ClaimResult;
import org.thingsboard.server.dao.device.claim.ReclaimResult;
import org.thingsboard.server.dao.exception.IncorrectParameterException;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.device.DeviceBulkImportService;
import org.thingsboard.server.service.importing.BulkImportRequest;
import org.thingsboard.server.service.importing.BulkImportResult;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.Operation;
import org.thingsboard.server.service.security.permission.Resource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_ID;
import static org.thingsboard.server.controller.ControllerConstants.CUSTOMER_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_INFO_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_NAME_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_PROFILE_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_SORT_PROPERTY_ALLOWABLE_VALUES;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_TEXT_SEARCH_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_TYPE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_WITH_DEVICE_CREDENTIALS_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.DEVICE_WITH_DEVICE_CREDENTIALS_PARAM_DESCRIPTION_MARKDOWN;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_ASSIGN_ASYNC_FIRST_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_ASSIGN_RECEIVE_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_UNASSIGN_ASYNC_FIRST_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.EDGE_UNASSIGN_RECEIVE_STEP_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_DATA_PARAMETERS;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_ALLOWABLE_VALUES;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.EdgeController.EDGE_ID;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class DeviceController extends BaseController {

    protected static final String DEVICE_ID = "deviceId";
    protected static final String DEVICE_NAME = "deviceName";
    protected static final String TENANT_ID = "tenantId";

    private final DeviceBulkImportService deviceBulkImportService;

    @ApiOperation(value = "Get Device (getDeviceById)",
            notes = "Fetch the Device object based on the provided Device Id. " +
                    "If the user has the authority of 'TENANT_ADMIN', the server checks that the device is owned by the same tenant. " +
                    "If the user has the authority of 'CUSTOMER_USER', the server checks that the device is assigned to the same customer." +
                    TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/device/{deviceId}", method = RequestMethod.GET)
    @ResponseBody
    public Device getDeviceById(@ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            return checkDeviceId(deviceId, Operation.READ);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Device Info (getDeviceInfoById)",
            notes = "Fetch the Device Info object based on the provided Device Id. " +
                    "If the user has the authority of 'Tenant Administrator', the server checks that the device is owned by the same tenant. " +
                    "If the user has the authority of 'Customer User', the server checks that the device is assigned to the same customer. " +
                    DEVICE_INFO_DESCRIPTION + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/device/info/{deviceId}", method = RequestMethod.GET)
    @ResponseBody
    public DeviceInfo getDeviceInfoById(@ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                        @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            return checkDeviceInfoId(deviceId, Operation.READ);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Create Or Update Device (saveDevice)",
            notes = "Create or update the Device. When creating device, platform generates Device Id as [time-based UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier#Version_1_(date-time_and_MAC_address). " +
                    "Device credentials are also generated if not provided in the 'accessToken' request parameter. " +
                    "The newly created device id will be present in the response. " +
                    "Specify existing Device id to update the device. " +
                    "Referencing non-existing device Id will cause 'Not Found' error." +
                    "\n\nDevice name is unique in the scope of tenant. Use unique identifiers like MAC or IMEI for the device names and non-unique 'label' field for user-friendly visualization purposes."
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/device", method = RequestMethod.POST)
    @ResponseBody
    public Device saveDevice(@ApiParam(value = "A JSON value representing the device.") @RequestBody Device device,
                             @ApiParam(value = "Optional value of the device credentials to be used during device creation. " +
                                     "If omitted, access token will be auto-generated.") @RequestParam(name = "accessToken", required = false) String accessToken) throws ThingsboardException {
        boolean created = device.getId() == null;
        try {
            device.setTenantId(getCurrentUser().getTenantId());

            Device oldDevice = null;
            if (!created) {
                oldDevice = checkDeviceId(device.getId(), Operation.WRITE);
            } else {
                checkEntity(null, device, Resource.DEVICE);
            }

            Device savedDevice = checkNotNull(deviceService.saveDeviceWithAccessToken(device, accessToken));

            onDeviceCreatedOrUpdated(savedDevice, oldDevice, !created, getCurrentUser());

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), device,
                    null, created ? ActionType.ADDED : ActionType.UPDATED, e);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Create Device (saveDevice) with credentials ",
            notes = "Create or update the Device. When creating device, platform generates Device Id as [time-based UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier#Version_1_(date-time_and_MAC_address). " +
                    "Requires to provide the Device Credentials object as well. Useful to create device and credentials in one request. " +
                    "You may find the example of LwM2M device and RPK credentials below: \n\n" +
                    DEVICE_WITH_DEVICE_CREDENTIALS_PARAM_DESCRIPTION_MARKDOWN +
                    TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/device-with-credentials", method = RequestMethod.POST)
    @ResponseBody
    public Device saveDeviceWithCredentials(@ApiParam(value = "The JSON object with device and credentials. See method description above for example.")
                                            @RequestBody SaveDeviceWithCredentialsRequest deviceAndCredentials) throws ThingsboardException {
        Device device = checkNotNull(deviceAndCredentials.getDevice());
        DeviceCredentials credentials = checkNotNull(deviceAndCredentials.getCredentials());
        boolean created = device.getId() == null;
        try {
            device.setTenantId(getCurrentUser().getTenantId());
            checkEntity(device.getId(), device, Resource.DEVICE);
            Device savedDevice = deviceService.saveDeviceWithCredentials(device, credentials);
            checkNotNull(savedDevice);
            tbClusterService.onDeviceUpdated(savedDevice, device);
            logEntityAction(savedDevice.getId(), savedDevice,
                    savedDevice.getCustomerId(),
                    device.getId() == null ? ActionType.ADDED : ActionType.UPDATED, null);

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), device,
                    null, created ? ActionType.ADDED : ActionType.UPDATED, e);
            throw handleException(e);
        }
    }

    private void onDeviceCreatedOrUpdated(Device savedDevice, Device oldDevice, boolean updated, SecurityUser user) {
        tbClusterService.onDeviceUpdated(savedDevice, oldDevice);

        try {
            logEntityAction(user, savedDevice.getId(), savedDevice,
                    savedDevice.getCustomerId(),
                    updated ? ActionType.UPDATED : ActionType.ADDED, null);
        } catch (ThingsboardException e) {
            log.error("Failed to log entity action", e);
        }
    }

    @ApiOperation(value = "Delete device (deleteDevice)",
            notes = "Deletes the device, it's credentials and all the relations (from and to the device). Referencing non-existing device Id will cause an error." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/device/{deviceId}", method = RequestMethod.DELETE)
    @ResponseStatus(value = HttpStatus.OK)
    public void deleteDevice(@ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                             @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            Device device = checkDeviceId(deviceId, Operation.DELETE);

            List<EdgeId> relatedEdgeIds = findRelatedEdgeIds(getTenantId(), deviceId);

            deviceService.deleteDevice(getCurrentUser().getTenantId(), deviceId);

            tbClusterService.onDeviceDeleted(device, null);

            logEntityAction(deviceId, device,
                    device.getCustomerId(),
                    ActionType.DELETED, null, strDeviceId);

            sendDeleteNotificationMsg(getTenantId(), deviceId, relatedEdgeIds);
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE),
                    null,
                    null,
                    ActionType.DELETED, e, strDeviceId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Assign device to customer (assignDeviceToCustomer)",
            notes = "Creates assignment of the device to customer. Customer will be able to query device afterwards." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/customer/{customerId}/device/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public Device assignDeviceToCustomer(@ApiParam(value = CUSTOMER_ID_PARAM_DESCRIPTION)
                                         @PathVariable("customerId") String strCustomerId,
                                         @ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                         @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter("customerId", strCustomerId);
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            Customer customer = checkCustomerId(customerId, Operation.READ);

            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            checkDeviceId(deviceId, Operation.ASSIGN_TO_CUSTOMER);

            Device savedDevice = checkNotNull(deviceService.assignDeviceToCustomer(getCurrentUser().getTenantId(), deviceId, customerId));

            logEntityAction(deviceId, savedDevice,
                    savedDevice.getCustomerId(),
                    ActionType.ASSIGNED_TO_CUSTOMER, null, strDeviceId, strCustomerId, customer.getName());

            sendEntityAssignToCustomerNotificationMsg(savedDevice.getTenantId(), savedDevice.getId(),
                    customerId, EdgeEventActionType.ASSIGNED_TO_CUSTOMER);

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.ASSIGNED_TO_CUSTOMER, e, strDeviceId, strCustomerId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Unassign device from customer (unassignDeviceFromCustomer)",
            notes = "Clears assignment of the device to customer. Customer will not be able to query device afterwards." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/customer/device/{deviceId}", method = RequestMethod.DELETE)
    @ResponseBody
    public Device unassignDeviceFromCustomer(@ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                             @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            Device device = checkDeviceId(deviceId, Operation.UNASSIGN_FROM_CUSTOMER);
            if (device.getCustomerId() == null || device.getCustomerId().getId().equals(ModelConstants.NULL_UUID)) {
                throw new IncorrectParameterException("Device isn't assigned to any customer!");
            }
            Customer customer = checkCustomerId(device.getCustomerId(), Operation.READ);

            Device savedDevice = checkNotNull(deviceService.unassignDeviceFromCustomer(getCurrentUser().getTenantId(), deviceId));

            logEntityAction(deviceId, device,
                    device.getCustomerId(),
                    ActionType.UNASSIGNED_FROM_CUSTOMER, null, strDeviceId, customer.getId().toString(), customer.getName());

            sendEntityAssignToCustomerNotificationMsg(savedDevice.getTenantId(), savedDevice.getId(),
                    customer.getId(), EdgeEventActionType.UNASSIGNED_FROM_CUSTOMER);

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.UNASSIGNED_FROM_CUSTOMER, e, strDeviceId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Make device publicly available (assignDeviceToPublicCustomer)",
            notes = "Device will be available for non-authorized (not logged-in) users. " +
                    "This is useful to create dashboards that you plan to share/embed on a publicly available website. " +
                    "However, users that are logged-in and belong to different tenant will not be able to access the device." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/customer/public/device/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public Device assignDeviceToPublicCustomer(@ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                               @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            Device device = checkDeviceId(deviceId, Operation.ASSIGN_TO_CUSTOMER);
            Customer publicCustomer = customerService.findOrCreatePublicCustomer(device.getTenantId());
            Device savedDevice = checkNotNull(deviceService.assignDeviceToCustomer(getCurrentUser().getTenantId(), deviceId, publicCustomer.getId()));

            logEntityAction(deviceId, savedDevice,
                    savedDevice.getCustomerId(),
                    ActionType.ASSIGNED_TO_CUSTOMER, null, strDeviceId, publicCustomer.getId().toString(), publicCustomer.getName());

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.ASSIGNED_TO_CUSTOMER, e, strDeviceId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Device Credentials (getDeviceCredentialsByDeviceId)",
            notes = "If during device creation there wasn't specified any credentials, platform generates random 'ACCESS_TOKEN' credentials." + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/device/{deviceId}/credentials", method = RequestMethod.GET)
    @ResponseBody
    public DeviceCredentials getDeviceCredentialsByDeviceId(@ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                                            @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            Device device = checkDeviceId(deviceId, Operation.READ_CREDENTIALS);
            DeviceCredentials deviceCredentials = checkNotNull(deviceCredentialsService.findDeviceCredentialsByDeviceId(getCurrentUser().getTenantId(), deviceId));
            logEntityAction(deviceId, device,
                    device.getCustomerId(),
                    ActionType.CREDENTIALS_READ, null, strDeviceId);
            return deviceCredentials;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.CREDENTIALS_READ, e, strDeviceId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Update device credentials (updateDeviceCredentials)", notes = "During device creation, platform generates random 'ACCESS_TOKEN' credentials. " +
            "Use this method to update the device credentials. First use 'getDeviceCredentialsByDeviceId' to get the credentials id and value. " +
            "Then use current method to update the credentials type and value. It is not possible to create multiple device credentials for the same device. " +
            "The structure of device credentials id and value is simple for the 'ACCESS_TOKEN' but is much more complex for the 'MQTT_BASIC' or 'LWM2M_CREDENTIALS'." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/device/credentials", method = RequestMethod.POST)
    @ResponseBody
    public DeviceCredentials updateDeviceCredentials(
            @ApiParam(value = "A JSON value representing the device credentials.")
            @RequestBody DeviceCredentials deviceCredentials) throws ThingsboardException {
        checkNotNull(deviceCredentials);
        try {
            Device device = checkDeviceId(deviceCredentials.getDeviceId(), Operation.WRITE_CREDENTIALS);
            DeviceCredentials result = checkNotNull(deviceCredentialsService.updateDeviceCredentials(getCurrentUser().getTenantId(), deviceCredentials));
            tbClusterService.pushMsgToCore(new DeviceCredentialsUpdateNotificationMsg(getCurrentUser().getTenantId(), deviceCredentials.getDeviceId(), result), null);

            sendEntityNotificationMsg(getTenantId(), device.getId(), EdgeEventActionType.CREDENTIALS_UPDATED);

            logEntityAction(device.getId(), device,
                    device.getCustomerId(),
                    ActionType.CREDENTIALS_UPDATED, null, deviceCredentials);
            return result;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.CREDENTIALS_UPDATED, e, deviceCredentials);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Tenant Devices (getTenantDevices)",
            notes = "Returns a page of devices owned by tenant. " +
                    PAGE_DATA_PARAMETERS + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/devices", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<Device> getTenantDevices(
            @ApiParam(value = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @ApiParam(value = DEVICE_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = DEVICE_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = DEVICE_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(deviceService.findDevicesByTenantIdAndType(tenantId, type, pageLink));
            } else {
                return checkNotNull(deviceService.findDevicesByTenantId(tenantId, pageLink));
            }
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Tenant Device Infos (getTenantDeviceInfos)",
            notes = "Returns a page of devices info objects owned by tenant. " +
                    PAGE_DATA_PARAMETERS + DEVICE_INFO_DESCRIPTION + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/deviceInfos", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<DeviceInfo> getTenantDeviceInfos(
            @ApiParam(value = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @ApiParam(value = DEVICE_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = DEVICE_PROFILE_ID_PARAM_DESCRIPTION)
            @RequestParam(required = false) String deviceProfileId,
            @ApiParam(value = DEVICE_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = DEVICE_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder
    ) throws ThingsboardException {
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(deviceService.findDeviceInfosByTenantIdAndType(tenantId, type, pageLink));
            } else if (deviceProfileId != null && deviceProfileId.length() > 0) {
                DeviceProfileId profileId = new DeviceProfileId(toUUID(deviceProfileId));
                return checkNotNull(deviceService.findDeviceInfosByTenantIdAndDeviceProfileId(tenantId, profileId, pageLink));
            } else {
                return checkNotNull(deviceService.findDeviceInfosByTenantId(tenantId, pageLink));
            }
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Tenant Device (getTenantDevice)",
            notes = "Requested device must be owned by tenant that the user belongs to. " +
                    "Device name is an unique property of device. So it can be used to identify the device." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/devices", params = {"deviceName"}, method = RequestMethod.GET)
    @ResponseBody
    public Device getTenantDevice(
            @ApiParam(value = DEVICE_NAME_DESCRIPTION)
            @RequestParam String deviceName) throws ThingsboardException {
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            return checkNotNull(deviceService.findDeviceByTenantIdAndName(tenantId, deviceName));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Customer Devices (getCustomerDevices)",
            notes = "Returns a page of devices objects assigned to customer. " +
                    PAGE_DATA_PARAMETERS + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}/devices", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<Device> getCustomerDevices(
            @ApiParam(value = CUSTOMER_ID_PARAM_DESCRIPTION, required = true)
            @PathVariable(CUSTOMER_ID) String strCustomerId,
            @ApiParam(value = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @ApiParam(value = DEVICE_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = DEVICE_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = DEVICE_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        checkParameter("customerId", strCustomerId);
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            checkCustomerId(customerId, Operation.READ);
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(deviceService.findDevicesByTenantIdAndCustomerIdAndType(tenantId, customerId, type, pageLink));
            } else {
                return checkNotNull(deviceService.findDevicesByTenantIdAndCustomerId(tenantId, customerId, pageLink));
            }
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Customer Device Infos (getCustomerDeviceInfos)",
            notes = "Returns a page of devices info objects assigned to customer. " +
                    PAGE_DATA_PARAMETERS + DEVICE_INFO_DESCRIPTION + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/{customerId}/deviceInfos", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<DeviceInfo> getCustomerDeviceInfos(
            @ApiParam(value = CUSTOMER_ID_PARAM_DESCRIPTION, required = true)
            @PathVariable("customerId") String strCustomerId,
            @ApiParam(value = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @ApiParam(value = DEVICE_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = DEVICE_PROFILE_ID_PARAM_DESCRIPTION)
            @RequestParam(required = false) String deviceProfileId,
            @ApiParam(value = DEVICE_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = DEVICE_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        checkParameter("customerId", strCustomerId);
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            CustomerId customerId = new CustomerId(toUUID(strCustomerId));
            checkCustomerId(customerId, Operation.READ);
            PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
            if (type != null && type.trim().length() > 0) {
                return checkNotNull(deviceService.findDeviceInfosByTenantIdAndCustomerIdAndType(tenantId, customerId, type, pageLink));
            } else if (deviceProfileId != null && deviceProfileId.length() > 0) {
                DeviceProfileId profileId = new DeviceProfileId(toUUID(deviceProfileId));
                return checkNotNull(deviceService.findDeviceInfosByTenantIdAndCustomerIdAndDeviceProfileId(tenantId, customerId, profileId, pageLink));
            } else {
                return checkNotNull(deviceService.findDeviceInfosByTenantIdAndCustomerId(tenantId, customerId, pageLink));
            }
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Devices By Ids (getDevicesByIds)",
            notes = "Requested devices must be owned by tenant or assigned to customer which user is performing the request. " + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/devices", params = {"deviceIds"}, method = RequestMethod.GET)
    @ResponseBody
    public List<Device> getDevicesByIds(
            @ApiParam(value = "A list of devices ids, separated by comma ','")
            @RequestParam("deviceIds") String[] strDeviceIds) throws ThingsboardException {
        checkArrayParameter("deviceIds", strDeviceIds);
        try {
            SecurityUser user = getCurrentUser();
            TenantId tenantId = user.getTenantId();
            CustomerId customerId = user.getCustomerId();
            List<DeviceId> deviceIds = new ArrayList<>();
            for (String strDeviceId : strDeviceIds) {
                deviceIds.add(new DeviceId(toUUID(strDeviceId)));
            }
            ListenableFuture<List<Device>> devices;
            if (customerId == null || customerId.isNullUid()) {
                devices = deviceService.findDevicesByTenantIdAndIdsAsync(tenantId, deviceIds);
            } else {
                devices = deviceService.findDevicesByTenantIdCustomerIdAndIdsAsync(tenantId, customerId, deviceIds);
            }
            return checkNotNull(devices.get());
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Find related devices (findByQuery)",
            notes = "Returns all devices that are related to the specific entity. " +
                    "The entity id, relation type, device types, depth of the search, and other query parameters defined using complex 'DeviceSearchQuery' object. " +
                    "See 'Model' tab of the Parameters for more info." + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/devices", method = RequestMethod.POST)
    @ResponseBody
    public List<Device> findByQuery(
            @ApiParam(value = "The device search query JSON")
            @RequestBody DeviceSearchQuery query) throws ThingsboardException {
        checkNotNull(query);
        checkNotNull(query.getParameters());
        checkNotNull(query.getDeviceTypes());
        checkEntityId(query.getParameters().getEntityId(), Operation.READ);
        try {
            List<Device> devices = checkNotNull(deviceService.findDevicesByQuery(getCurrentUser().getTenantId(), query).get());
            devices = devices.stream().filter(device -> {
                try {
                    accessControlService.checkPermission(getCurrentUser(), Resource.DEVICE, Operation.READ, device.getId(), device);
                    return true;
                } catch (ThingsboardException e) {
                    return false;
                }
            }).collect(Collectors.toList());
            return devices;
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get Device Types (getDeviceTypes)",
            notes = "Returns a set of unique device profile names based on devices that are either owned by the tenant or assigned to the customer which user is performing the request."
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/device/types", method = RequestMethod.GET)
    @ResponseBody
    public List<EntitySubtype> getDeviceTypes() throws ThingsboardException {
        try {
            SecurityUser user = getCurrentUser();
            TenantId tenantId = user.getTenantId();
            ListenableFuture<List<EntitySubtype>> deviceTypes = deviceService.findDeviceTypesByTenantId(tenantId);
            return checkNotNull(deviceTypes.get());
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Claim device (claimDevice)",
            notes = "Claiming makes it possible to assign a device to the specific customer using device/server side claiming data (in the form of secret key)." +
                    "To make this happen you have to provide unique device name and optional claiming data (it is needed only for device-side claiming)." +
                    "Once device is claimed, the customer becomes its owner and customer users may access device data as well as control the device. \n" +
                    "In order to enable claiming devices feature a system parameter security.claim.allowClaimingByDefault should be set to true, " +
                    "otherwise a server-side claimingAllowed attribute with the value true is obligatory for provisioned devices. \n" +
                    "See official documentation for more details regarding claiming." + CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('CUSTOMER_USER')")
    @RequestMapping(value = "/customer/device/{deviceName}/claim", method = RequestMethod.POST)
    @ResponseBody
    public DeferredResult<ResponseEntity> claimDevice(@ApiParam(value = "Unique name of the device which is going to be claimed")
                                                      @PathVariable(DEVICE_NAME) String deviceName,
                                                      @ApiParam(value = "Claiming request which can optionally contain secret key")
                                                      @RequestBody(required = false) ClaimRequest claimRequest) throws ThingsboardException {
        checkParameter(DEVICE_NAME, deviceName);
        try {
            final DeferredResult<ResponseEntity> deferredResult = new DeferredResult<>();

            SecurityUser user = getCurrentUser();
            TenantId tenantId = user.getTenantId();
            CustomerId customerId = user.getCustomerId();

            Device device = checkNotNull(deviceService.findDeviceByTenantIdAndName(tenantId, deviceName));
            accessControlService.checkPermission(user, Resource.DEVICE, Operation.CLAIM_DEVICES,
                    device.getId(), device);
            String secretKey = getSecretKey(claimRequest);

            ListenableFuture<ClaimResult> future = claimDevicesService.claimDevice(device, customerId, secretKey);
            Futures.addCallback(future, new FutureCallback<ClaimResult>() {
                @Override
                public void onSuccess(@Nullable ClaimResult result) {
                    HttpStatus status;
                    if (result != null) {
                        if (result.getResponse().equals(ClaimResponse.SUCCESS)) {
                            status = HttpStatus.OK;
                            deferredResult.setResult(new ResponseEntity<>(result, status));

                            try {
                                logEntityAction(user, device.getId(), result.getDevice(), customerId, ActionType.ASSIGNED_TO_CUSTOMER, null,
                                        device.getId().toString(), customerId.toString(), customerService.findCustomerById(tenantId, customerId).getName());
                            } catch (ThingsboardException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            status = HttpStatus.BAD_REQUEST;
                            deferredResult.setResult(new ResponseEntity<>(result.getResponse(), status));
                        }
                    } else {
                        deferredResult.setResult(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    deferredResult.setErrorResult(t);
                }
            }, MoreExecutors.directExecutor());
            return deferredResult;
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Reclaim device (reClaimDevice)",
            notes = "Reclaiming means the device will be unassigned from the customer and the device will be available for claiming again."
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/customer/device/{deviceName}/claim", method = RequestMethod.DELETE)
    @ResponseStatus(value = HttpStatus.OK)
    public DeferredResult<ResponseEntity> reClaimDevice(@ApiParam(value = "Unique name of the device which is going to be reclaimed")
                                                        @PathVariable(DEVICE_NAME) String deviceName) throws ThingsboardException {
        checkParameter(DEVICE_NAME, deviceName);
        try {
            final DeferredResult<ResponseEntity> deferredResult = new DeferredResult<>();

            SecurityUser user = getCurrentUser();
            TenantId tenantId = user.getTenantId();

            Device device = checkNotNull(deviceService.findDeviceByTenantIdAndName(tenantId, deviceName));
            accessControlService.checkPermission(user, Resource.DEVICE, Operation.CLAIM_DEVICES,
                    device.getId(), device);

            ListenableFuture<ReclaimResult> result = claimDevicesService.reClaimDevice(tenantId, device);
            Futures.addCallback(result, new FutureCallback<>() {
                @Override
                public void onSuccess(ReclaimResult reclaimResult) {
                    deferredResult.setResult(new ResponseEntity(HttpStatus.OK));

                    Customer unassignedCustomer = reclaimResult.getUnassignedCustomer();
                    if (unassignedCustomer != null) {
                        try {
                            logEntityAction(user, device.getId(), device, device.getCustomerId(), ActionType.UNASSIGNED_FROM_CUSTOMER, null,
                                    device.getId().toString(), unassignedCustomer.getId().toString(), unassignedCustomer.getName());
                        } catch (ThingsboardException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    deferredResult.setErrorResult(t);
                }
            }, MoreExecutors.directExecutor());
            return deferredResult;
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    private String getSecretKey(ClaimRequest claimRequest) throws IOException {
        String secretKey = claimRequest.getSecretKey();
        if (secretKey != null) {
            return secretKey;
        }
        return DataConstants.DEFAULT_SECRET_KEY;
    }

    @ApiOperation(value = "Assign device to tenant (assignDeviceToTenant)",
            notes = "Creates assignment of the device to tenant. Thereafter tenant will be able to reassign the device to a customer." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/tenant/{tenantId}/device/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public Device assignDeviceToTenant(@ApiParam(value = TENANT_ID_PARAM_DESCRIPTION)
                                       @PathVariable(TENANT_ID) String strTenantId,
                                       @ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                       @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(TENANT_ID, strTenantId);
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            Device device = checkDeviceId(deviceId, Operation.ASSIGN_TO_TENANT);

            TenantId newTenantId = new TenantId(toUUID(strTenantId));
            Tenant newTenant = tenantService.findTenantById(newTenantId);
            if (newTenant == null) {
                throw new ThingsboardException("Could not find the specified Tenant!", ThingsboardErrorCode.BAD_REQUEST_PARAMS);
            }

            Device assignedDevice = deviceService.assignDeviceToTenant(newTenantId, device);

            logEntityAction(getCurrentUser(), deviceId, assignedDevice,
                    assignedDevice.getCustomerId(),
                    ActionType.ASSIGNED_TO_TENANT, null, strTenantId, newTenant.getName());

            Tenant currentTenant = tenantService.findTenantById(getTenantId());
            pushAssignedFromNotification(currentTenant, newTenantId, assignedDevice);

            return assignedDevice;
        } catch (Exception e) {
            logEntityAction(getCurrentUser(), emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.ASSIGNED_TO_TENANT, e, strTenantId);
            throw handleException(e);
        }
    }

    private void pushAssignedFromNotification(Tenant currentTenant, TenantId newTenantId, Device assignedDevice) {
        String data = entityToStr(assignedDevice);
        if (data != null) {
            TbMsg tbMsg = TbMsg.newMsg(DataConstants.ENTITY_ASSIGNED_FROM_TENANT, assignedDevice.getId(), assignedDevice.getCustomerId(), getMetaDataForAssignedFrom(currentTenant), TbMsgDataType.JSON, data);
            tbClusterService.pushMsgToRuleEngine(newTenantId, assignedDevice.getId(), tbMsg, null);
        }
    }

    private TbMsgMetaData getMetaDataForAssignedFrom(Tenant tenant) {
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("assignedFromTenantId", tenant.getId().getId().toString());
        metaData.putValue("assignedFromTenantName", tenant.getName());
        return metaData;
    }

    @ApiOperation(value = "Assign device to edge (assignDeviceToEdge)",
            notes = "Creates assignment of an existing device to an instance of The Edge. " +
                    EDGE_ASSIGN_ASYNC_FIRST_STEP_DESCRIPTION +
                    "Second, remote edge service will receive a copy of assignment device " +
                    EDGE_ASSIGN_RECEIVE_STEP_DESCRIPTION + ". " +
                    "Third, once device will be delivered to edge service, it's going to be available for usage on remote edge instance." + TENANT_AUTHORITY_PARAGRAPH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/edge/{edgeId}/device/{deviceId}", method = RequestMethod.POST)
    @ResponseBody
    public Device assignDeviceToEdge(@ApiParam(value = EDGE_ID_PARAM_DESCRIPTION)
                                     @PathVariable(EDGE_ID) String strEdgeId,
                                     @ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                     @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(EDGE_ID, strEdgeId);
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            EdgeId edgeId = new EdgeId(toUUID(strEdgeId));
            Edge edge = checkEdgeId(edgeId, Operation.READ);

            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            checkDeviceId(deviceId, Operation.READ);

            Device savedDevice = checkNotNull(deviceService.assignDeviceToEdge(getCurrentUser().getTenantId(), deviceId, edgeId));

            tbClusterService.pushMsgToCore(new DeviceEdgeUpdateMsg(savedDevice.getTenantId(),
                    savedDevice.getId(), edgeId), null);

            logEntityAction(deviceId, savedDevice,
                    savedDevice.getCustomerId(),
                    ActionType.ASSIGNED_TO_EDGE, null, strDeviceId, strEdgeId, edge.getName());

            sendEntityAssignToEdgeNotificationMsg(getTenantId(), edgeId, savedDevice.getId(), EdgeEventActionType.ASSIGNED_TO_EDGE);

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.ASSIGNED_TO_EDGE, e, strDeviceId, strEdgeId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Unassign device from edge (unassignDeviceFromEdge)",
            notes = "Clears assignment of the device to the edge. " +
                    EDGE_UNASSIGN_ASYNC_FIRST_STEP_DESCRIPTION +
                    "Second, remote edge service will receive an 'unassign' command to remove device " +
                    EDGE_UNASSIGN_RECEIVE_STEP_DESCRIPTION + ". " +
                    "Third, once 'unassign' command will be delivered to edge service, it's going to remove device locally." + TENANT_AUTHORITY_PARAGRAPH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/edge/{edgeId}/device/{deviceId}", method = RequestMethod.DELETE)
    @ResponseBody
    public Device unassignDeviceFromEdge(@ApiParam(value = EDGE_ID_PARAM_DESCRIPTION)
                                         @PathVariable(EDGE_ID) String strEdgeId,
                                         @ApiParam(value = DEVICE_ID_PARAM_DESCRIPTION)
                                         @PathVariable(DEVICE_ID) String strDeviceId) throws ThingsboardException {
        checkParameter(EDGE_ID, strEdgeId);
        checkParameter(DEVICE_ID, strDeviceId);
        try {
            EdgeId edgeId = new EdgeId(toUUID(strEdgeId));
            Edge edge = checkEdgeId(edgeId, Operation.READ);

            DeviceId deviceId = new DeviceId(toUUID(strDeviceId));
            Device device = checkDeviceId(deviceId, Operation.READ);

            Device savedDevice = checkNotNull(deviceService.unassignDeviceFromEdge(getCurrentUser().getTenantId(), deviceId, edgeId));

            tbClusterService.pushMsgToCore(new DeviceEdgeUpdateMsg(savedDevice.getTenantId(),
                    savedDevice.getId(), null), null);

            logEntityAction(deviceId, device,
                    device.getCustomerId(),
                    ActionType.UNASSIGNED_FROM_EDGE, null, strDeviceId, strEdgeId, edge.getName());

            sendEntityAssignToEdgeNotificationMsg(getTenantId(), edgeId, savedDevice.getId(), EdgeEventActionType.UNASSIGNED_FROM_EDGE);

            return savedDevice;
        } catch (Exception e) {
            logEntityAction(emptyId(EntityType.DEVICE), null,
                    null,
                    ActionType.UNASSIGNED_FROM_EDGE, e, strDeviceId, strEdgeId);
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Get devices assigned to edge (getEdgeDevices)",
            notes = "Returns a page of devices assigned to edge. " +
                    PAGE_DATA_PARAMETERS + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/edge/{edgeId}/devices", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<Device> getEdgeDevices(
            @ApiParam(value = EDGE_ID_PARAM_DESCRIPTION, required = true)
            @PathVariable(EDGE_ID) String strEdgeId,
            @ApiParam(value = PAGE_SIZE_DESCRIPTION, required = true)
            @RequestParam int pageSize,
            @ApiParam(value = PAGE_NUMBER_DESCRIPTION, required = true)
            @RequestParam int page,
            @ApiParam(value = DEVICE_TYPE_DESCRIPTION)
            @RequestParam(required = false) String type,
            @ApiParam(value = DEVICE_TEXT_SEARCH_DESCRIPTION)
            @RequestParam(required = false) String textSearch,
            @ApiParam(value = SORT_PROPERTY_DESCRIPTION, allowableValues = DEVICE_SORT_PROPERTY_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortProperty,
            @ApiParam(value = SORT_ORDER_DESCRIPTION, allowableValues = SORT_ORDER_ALLOWABLE_VALUES)
            @RequestParam(required = false) String sortOrder,
            @ApiParam(value = "Timestamp. Devices with creation time before it won't be queried")
            @RequestParam(required = false) Long startTime,
            @ApiParam(value = "Timestamp. Devices with creation time after it won't be queried")
            @RequestParam(required = false) Long endTime) throws ThingsboardException {
        checkParameter(EDGE_ID, strEdgeId);
        try {
            TenantId tenantId = getCurrentUser().getTenantId();
            EdgeId edgeId = new EdgeId(toUUID(strEdgeId));
            checkEdgeId(edgeId, Operation.READ);
            TimePageLink pageLink = createTimePageLink(pageSize, page, textSearch, sortProperty, sortOrder, startTime, endTime);
            PageData<Device> nonFilteredResult;
            if (type != null && type.trim().length() > 0) {
                nonFilteredResult = deviceService.findDevicesByTenantIdAndEdgeIdAndType(tenantId, edgeId, type, pageLink);
            } else {
                nonFilteredResult = deviceService.findDevicesByTenantIdAndEdgeId(tenantId, edgeId, pageLink);
            }
            List<Device> filteredDevices = nonFilteredResult.getData().stream().filter(device -> {
                try {
                    accessControlService.checkPermission(getCurrentUser(), Resource.DEVICE, Operation.READ, device.getId(), device);
                    return true;
                } catch (ThingsboardException e) {
                    return false;
                }
            }).collect(Collectors.toList());
            PageData<Device> filteredResult = new PageData<>(filteredDevices,
                    nonFilteredResult.getTotalPages(),
                    nonFilteredResult.getTotalElements(),
                    nonFilteredResult.hasNext());
            return checkNotNull(filteredResult);
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Count devices by device profile  (countByDeviceProfileAndEmptyOtaPackage)",
            notes = "The platform gives an ability to load OTA (over-the-air) packages to devices. " +
                    "It can be done in two different ways: device scope or device profile scope." +
                    "In the response you will find the number of devices with specified device profile, but without previously defined device scope OTA package. " +
                    "It can be useful when you want to define number of devices that will be affected with future OTA package" + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    @RequestMapping(value = "/devices/count/{otaPackageType}/{deviceProfileId}", method = RequestMethod.GET)
    @ResponseBody
    public Long countByDeviceProfileAndEmptyOtaPackage(@ApiParam(value = "OTA package type", allowableValues = "FIRMWARE, SOFTWARE")
                                                       @PathVariable("otaPackageType") String otaPackageType,
                                                       @ApiParam(value = "Device Profile Id. I.g. '784f394c-42b6-435a-983c-b7beff2784f9'")
                                                       @PathVariable("deviceProfileId") String deviceProfileId) throws ThingsboardException {
        checkParameter("OtaPackageType", otaPackageType);
        checkParameter("DeviceProfileId", deviceProfileId);
        try {
            return deviceService.countDevicesByTenantIdAndDeviceProfileIdAndEmptyOtaPackage(
                    getTenantId(),
                    new DeviceProfileId(UUID.fromString(deviceProfileId)),
                    OtaPackageType.valueOf(otaPackageType));
        } catch (Exception e) {
            throw handleException(e);
        }
    }

    @ApiOperation(value = "Import the bulk of devices (processDevicesBulkImport)",
            notes = "There's an ability to import the bulk of devices using the only .csv file." + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @PostMapping("/device/bulk_import")
    public BulkImportResult<Device> processDevicesBulkImport(@RequestBody BulkImportRequest request) throws Exception {
        SecurityUser user = getCurrentUser();
        return deviceBulkImportService.processBulkImport(request, user, importedDeviceInfo -> {
            onDeviceCreatedOrUpdated(importedDeviceInfo.getEntity(), importedDeviceInfo.getOldEntity(), importedDeviceInfo.isUpdated(), user);
        });
    }

}
