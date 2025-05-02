package com.HomeCare.demo.Service.Impl;

import com.HomeCare.demo.Entity.*;
import com.HomeCare.demo.Model.*;
import com.HomeCare.demo.Model.ResponseModel.*;
import com.HomeCare.demo.Repository.AdminProfileRepository;
import com.HomeCare.demo.Repository.PcaProfileRepository;
import com.HomeCare.demo.Repository.UserFileMasterRepository;
import com.HomeCare.demo.Repository.UserRepository;
import com.HomeCare.demo.Service.Interfaces.IPcaService;
import com.HomeCare.demo.Utils.Constants;
import com.HomeCare.demo.Utils.Exceptions.AlreadyExistException;
import com.HomeCare.demo.Utils.Exceptions.BadRequestException;
import com.HomeCare.demo.Utils.Exceptions.NotFoundException;
import com.HomeCare.demo.Utils.GeneralUtil;
import com.HomeCare.demo.Utils.PasswordHashing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
@Slf4j
@Service
public class PcaService implements IPcaService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PcaProfileRepository pcaProfileRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PatientService patientService;

    @Autowired
    private FolderService folderService;

    GeneralUtil utils = new GeneralUtil();

    private static final Long FILE_SIZE_THRESHOLD = 2108072L;
    @Autowired
    private UserFileMasterRepository userFileMasterRepository;

    @Override
    public Long pcaCreation(PcaUserDetails pcaUserDetails) {

        log.info("Entering pca creation......");
        validateRoleForSuperAdminRegAdmin(pcaUserDetails.getCreatedByUserRole());

        UserMaster existingUser = userRepository.findByEmailAndActive(pcaUserDetails.getUserEmail(), true);

        if(Objects.nonNull(existingUser)){
            throw new AlreadyExistException(Constants.USER_NAME_EXIST);
        }

        UserMaster user = new UserMaster();
        user.setUserLastName(pcaUserDetails.getUserLastName());
        if (pcaUserDetails.getUserMiddleName() != null && !pcaUserDetails.getUserMiddleName().isEmpty()) {
            user.setUserMiddleName(pcaUserDetails.getUserMiddleName());

        } else {
            user.setUserMiddleName("");
        }
        user.setUserEmail(pcaUserDetails.getUserEmail());
        user.setUserMobileNo(pcaUserDetails.getUserMobileNo());
        user.setUserCountryCode(pcaUserDetails.getMobileCountryCode());
        user.setAssignedTo(pcaUserDetails.getAuthorizedTo());

        String password = generatePassword();
        String hashedPassword = PasswordHashing.sha3256Algo(password);

        user.setUserPassword(hashedPassword);
        user.setUserFirstName(pcaUserDetails.getUserFirstName());
        user.setDateOfBirth(pcaUserDetails.getDateOfBirth());
        user.setIsFirstLogin(true);
        user.setUserHireDate(pcaUserDetails.getUserHireDate());
        user.setUserStatus(true);

        RoleMaster userRole = userService.findUserRole(3L);
        user.setRole(userRole);

        UserMaster userId = userRepository.save(user);

        PcaProfile pcaProfile = getPcaProfile(pcaUserDetails, userId);
        pcaProfileRepository.save(pcaProfile);

        LoginRequest request = new LoginRequest();
        request.setEmail(pcaUserDetails.getUserEmail());
        request.setPassword(password);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(()->{
            emailService.sendCredentials(request);
        });
        executorService.shutdown();

        log.info("Exiting pca creation......");
        return userId.getUserId();
    }

    @Override
    public Page<PcaListResponse> getAllPca(Pageable pageable) {
        log.info("Entering all pca list......");
        Page<UserMaster> allUsers = userRepository.findAllByRoleIdActive(3L , pageable);

        if(allUsers.isEmpty()){
            return Page.empty(pageable);
        }

        log.info("Exiting all pca list......");
        return allUsers.map(user -> {
            PcaListResponse pcaUser = new PcaListResponse();
            pcaUser.setUserClients(getPcaClients(user.getUserId()));
            pcaUser.setUserDocs(true);
            String userName;
            if (user.getUserMiddleName() == null || user.getUserMiddleName().isEmpty()) {
                userName = user.getUserFirstName() + " " + user.getUserLastName();
            } else {
                userName = user.getUserFirstName() + " " + user.getUserMiddleName().toCharArray()[0]+"." + " " + user.getUserLastName();
            }
            pcaUser.setUserName(userName);
            pcaUser.setUserMobileNumber(user.getUserMobileNo());
            pcaUser.setUserId(user.getUserId());
            pcaUser.setUserAssignedTo(user.getAssignedTo());
            pcaUser.setUserCpsId(getCpsId(user.getUserId()));
            pcaUser.setCountryCode(user.getUserCountryCode());
            return pcaUser;
        });

    }

    @Override
    public PcaProfileResponse pcaProfile(Long userID) {

        log.info("Entering pca profile......");
        UserMaster existingUser = userRepository.findUserByIdAndActive(userID , true);
        PcaProfileResponse pcaProfile = new PcaProfileResponse();

        if(Objects.nonNull(existingUser)){
            PcaProfile pcaDetails = pcaProfileRepository.findByUserId(existingUser.getUserId());
            pcaProfile.setUserId(existingUser.getUserId());
            pcaProfile.setDob(existingUser.getDateOfBirth());
            pcaProfile.setEmail(existingUser.getUserEmail());
            pcaProfile.setAddress(pcaDetails.getUserAddress());
            pcaProfile.setFirstName(existingUser.getUserFirstName());
            pcaProfile.setLastName(existingUser.getUserLastName());
            if (existingUser.getUserMiddleName() != null && !existingUser.getUserMiddleName().isEmpty()) {
                pcaProfile.setUserMiddleName(existingUser.getUserMiddleName());
            } else {
                pcaProfile.setUserMiddleName("");
            }
            pcaProfile.setCpsId(pcaDetails.getUserCpsId());
            pcaProfile.setPhoneNo(existingUser.getUserMobileNo());
            pcaProfile.setRoleId(existingUser.getRole().getRoleId());
            pcaProfile.setHhiId1(pcaDetails.getUserHhdId());
            pcaProfile.setHhiId2(pcaDetails.getUserHhdId2());
            pcaProfile.setCity(pcaDetails.getUserCity());
            pcaProfile.setState(pcaDetails.getUserState());
            pcaProfile.setZipCode(pcaDetails.getUserPostalCode());
            pcaProfile.setAssignedTo(existingUser.getAssignedTo());
            pcaProfile.setCountryCode(existingUser.getUserCountryCode());
            pcaProfile.setAdminName(getAdminName(existingUser.getAssignedTo()));
            pcaProfile.setProfilePic(getPcaProfilePic(existingUser.getUserId()));

        }
        else{
            throw new NotFoundException(Constants.EMAIL_NOT_EXIST);
        }
        log.info("Exiting pca profile......");
        return pcaProfile;
    }

    @Override
    public List<ClientsByPca> pcaListForClients(Long userId) {
        log.info("Entering pca list for clients......");
        List<UserMaster> clientsList = userRepository.findAllClientsForPca(userId , 4L);

        log.info("Exiting pca list for clients......");
        return clientsList.stream().map(user ->{
            ClientsByPca pcaClient = new ClientsByPca();

            pcaClient.setMobileNo(user.getUserMobileNo());
            pcaClient.setUserCountryCode(user.getUserCountryCode());
            String userName;
            if (user.getUserMiddleName() == null || user.getUserMiddleName().isEmpty()) {
                userName = user.getUserFirstName() + " " + user.getUserLastName();
            } else {
                userName = user.getUserFirstName() + " " + user.getUserMiddleName().toCharArray()[0]+"." + " " + user.getUserLastName();
            }
            pcaClient.setName(userName);
            pcaClient.setEmail(user.getUserEmail());
            pcaClient.setUserId(user.getUserId());
            pcaClient.setOnBoardedDate(user.getUserHireDate());
            pcaClient.setDateOfBirth(user.getDateOfBirth());
            pcaClient.setMaId(patientService.getPatientMaId(user.getUserId()));
            pcaClient.setInsuranceName(patientService.getPatientInsuranceName(user.getUserId()));
            pcaClient.setUserAddress(patientService.getPatientAddress(user.getUserId()));
            return pcaClient;
        }).collect(Collectors.toList());

    }

    @Override
    public List<PcaListForAdmin> unassigedPcaList(Long roleId) {

        log.info("Entering unassign Pca list......");
        validateRoleForSuperAdminRegAdmin(roleId);

        List<UserMaster> unassignedUsers = userRepository.findAllUnassignedPcaForAdmin(3L);

        if(Objects.isNull(unassignedUsers)){
            throw new NotFoundException(Constants.UNASSIGNED_PCA_LIST_EMPTY);
        }

        log.info("Exiting unassign Pca list......");
       return unassignedUsers.stream().map(user->{
            PcaListForAdmin pcaUser = new PcaListForAdmin();
            pcaUser.setUserId(user.getUserId());
            pcaUser.setAssignedTo(user.getAssignedTo());
           String userName;
           if (user.getUserMiddleName() == null || user.getUserMiddleName().isEmpty()) {
               userName = user.getUserFirstName() + " " + user.getUserLastName();
           } else {
               userName = user.getUserFirstName() + " " + user.getUserMiddleName().toCharArray()[0]+"." + " " + user.getUserLastName();
           }
            pcaUser.setUserName(userName);
            pcaUser.setCountryId(user.getUserCountryCode());
            pcaUser.setUserMobileNo(user.getUserMobileNo());
            pcaUser.setCpsId(getCpsId(user.getUserId()));
            pcaUser.setClients(getPcaClients(user.getUserId()));
            return pcaUser;
        }).collect(Collectors.toList());
    }

    @Override
    public PcaProfileUpdateRequest updatePcaProfile(PcaProfileUpdateRequest pcaProfileUpdateRequest) {

        log.info("Entering update pca profile......");
        UserMaster existingUser = userRepository.findByUserIdAndRole(pcaProfileUpdateRequest.getUserId(), 3L);

        if(Objects.isNull(existingUser)){
            throw new NotFoundException(Constants.EMAIL_NOT_EXIST);
        }

        if(existingUser.getUserStatus().equals(false)){
            throw new BadRequestException(Constants.USER_DEACTIVATED);
        }

        existingUser.setUserFirstName(pcaProfileUpdateRequest.getUserFirstName());
        if (pcaProfileUpdateRequest.getUserMiddleName() != null && !pcaProfileUpdateRequest.getUserMiddleName().isEmpty()) {
            existingUser.setUserMiddleName(pcaProfileUpdateRequest.getUserMiddleName());
        } else {
            existingUser.setUserMiddleName("");
        }
        existingUser.setUserLastName(pcaProfileUpdateRequest.getUserLastName());
        existingUser.setUserMobileNo(pcaProfileUpdateRequest.getUserMobileNo());
        existingUser.setUserCountryCode(pcaProfileUpdateRequest.getMobileCountryCode());
        existingUser.setDateOfBirth(pcaProfileUpdateRequest.getDateOfBirth());
        existingUser.setAssignedTo(pcaProfileUpdateRequest.getAssignedTo());

        userRepository.save(existingUser);

        PcaProfile profile = pcaProfileRepository.findByUserId(existingUser.getUserId());

        if(Objects.isNull(profile)){
            throw new NotFoundException(Constants.PCA_PROFILE_NOT_FOUND);
        }

        profile.setUserHhdId(pcaProfileUpdateRequest.getUserHhdId());
        profile.setUserHhdId2(pcaProfileUpdateRequest.getUserHhdId2());
        profile.setUserCpsId(pcaProfileUpdateRequest.getUserCpsId());
        profile.setUserAddress(pcaProfileUpdateRequest.getUserAddress());
        profile.setUserCountryCode(pcaProfileUpdateRequest.getMobileCountryCode());
        profile.setUserState(pcaProfileUpdateRequest.getState());
        profile.setUserCity(pcaProfileUpdateRequest.getCity());
        profile.setUserCountry(pcaProfileUpdateRequest.getCountry());
        profile.setUserPostalCode(pcaProfileUpdateRequest.getPostalCode());
        profile.setAuthorizedTo(pcaProfileUpdateRequest.getAssignedTo());

        pcaProfileRepository.save(profile);

        log.info("Exiting update pca profile......");
        return pcaProfileUpdateRequest;
    }

    @Override
    public void markPcaInactive(Long id) {
        log.info("Entering mark pca inactive......");
        UserMaster existingUser = userRepository.findUserByIdAndActive(id , true);

        if(Objects.isNull(existingUser)){
            throw new NotFoundException(Constants.EMAIL_NOT_EXIST);
        }
        if(existingUser.getUserStatus().equals(false)){
            throw new AlreadyExistException(Constants.PCA_ALREADY_MARKED_AS_INACTIVE);
        }
        existingUser.setUserStatus(false);
        userRepository.save(existingUser);

        List<UserMaster> patientsUsers = userRepository.findAllPcaForAdmin(id , 4L);

        if(Objects.nonNull(patientsUsers)) {
            patientsUsers.forEach(user -> {
                user.setAssignedTo(null);
                userRepository.save(user);
            });
        }

        List<UserFileMaster> userFiles = userFileMasterRepository.findAllFilesForUser(1 , existingUser.getUserId());
        List<UserFileMaster> updatedUserFiles = userFiles.stream().map(file->{
            file.setIsActive(false);
            return file;
        }).collect(Collectors.toList());
        userFileMasterRepository.saveAll(updatedUserFiles);
        log.info("Exiting mark pca inactive......");
    }

    @Override
    public void uploadFileForPca(MultipartFile file , Long userId, Long fileId, String fileName, String folderName, LocalDateTime graceTimePeriod, LocalDateTime fileExpiryDate, LocalDateTime fileNotificationDate, Long fileUploadedBy, Integer branchId) throws IOException {

        if(Objects.isNull(file)){
            throw new NotFoundException(Constants.NO_CONTENT_FOUND);
        }
        log.info("Entering upload file for the PCA......");
        String name = utils.generateFileName(file);

        long fileSize = file.getSize();
        if(Long.valueOf(fileSize).equals(0L)){
            throw new NotFoundException(Constants.NO_CONTENT_FOUND);
        }

        if(fileSize > FILE_SIZE_THRESHOLD){
            throw new BadRequestException(Constants.FILE_SIZE_EXCEEDED_2MB);
        }

        FileUploadRequest newFile = new FileUploadRequest();
        newFile.setFileName(fileName);
        newFile.setFileUploadedBy(fileUploadedBy);
        newFile.setFileExpiryDate(fileExpiryDate);
        newFile.setFileId(fileId);
        newFile.setFileNotificationDate(fileNotificationDate);
        newFile.setFolderName(folderName);
        newFile.setGraceTimePeriod(graceTimePeriod);
        newFile.setUserId(userId);
        newFile.setBranchId(branchId);

        folderService.uploadFileToS3(file , newFile, name, fileSize);
        log.info("Exiting upload file for the PCA......");
    }

    @Override
    public List<UserFileMaster> getAllFilesForPca(Long id , Integer branchId) {
        log.info("Entering all files for PCA......");
        List<UserFileMaster> userFiles = userFileMasterRepository.findAllFilesForUser(branchId , id);
        if(Objects.isNull(userFiles)){
            throw new NotFoundException(Constants.NO_DOCS_FOUND);
        }
        log.info("Exiting all files for PCA......");
        return userFiles.stream().filter(UserFileMaster::getIsActive).collect(Collectors.toList());
    }

    @Override
    public String assignPatientToPca(PcaPatientAssignRequest pcaPatientAssignRequest) {

        log.info("Entering assign Patient to Pca......");
        UserMaster existingUser = userRepository.findByUserIdAndRole(pcaPatientAssignRequest.getPcaId(), 3L);

        if(Objects.isNull(existingUser)){
            throw new BadRequestException(Constants.EMAIL_NOT_EXIST);
        }
        if(existingUser.getUserStatus().equals(false)){
            throw new BadRequestException(Constants.USER_DEACTIVATED);
        }

        List<UserMaster> unassignedPatientList = userRepository.findAllByUserIdInAndRoleAndAssignedToIsNull(pcaPatientAssignRequest.getPatientList() , 4L);

        for (UserMaster user : unassignedPatientList) {
            user.setAssignedTo(existingUser.getUserId());
        }

        userRepository.saveAll(unassignedPatientList);
        log.info("Exiting assign Patient to Pca......");
        if(pcaPatientAssignRequest.getPatientList().size() > 1){
            return "Patients assigned successfully";
        }
        else{
            return "Patient assigned successfully";
        }
    }

    @Override
    public List<PatientListForPca> getAllPatientsForPca(Long userId) {
        log.info("Entering get all patient for pca......");
        UserMaster existingUser = userRepository.findUserByIdAndActive(userId , true);

        if(ObjectUtils.isEmpty(existingUser)){
            throw new NotFoundException(Constants.PCA_LIST_EMPTY_FOR_ADMIN);
        }

        List<UserMaster> patientsList = userRepository.findAllPatientForPca(userId , 4L);

        if(ObjectUtils.isEmpty(patientsList)){
            throw new NotFoundException(Constants.PATIENT_LIST_NOT_FOUND);
        }

        log.info("Exiting get all patient for pca......");
        return patientsList.stream().map(user->{
            PatientListForPca newUser = new PatientListForPca();
            newUser.setUserId(user.getUserId());
            String userName;
            if (user.getUserMiddleName() == null || user.getUserMiddleName().isEmpty()) {
                userName = user.getUserFirstName() + " " + user.getUserLastName();
            } else {
                userName = user.getUserFirstName() + " " + user.getUserMiddleName().toCharArray()[0]+"." + " " + user.getUserLastName();
            }
            newUser.setPatientName(userName);
            newUser.setPatientEmail(user.getUserEmail());
            newUser.setCountryCode(user.getUserCountryCode());
            newUser.setDateAttended(user.getUserHireDate());
            newUser.setMobileNo(user.getUserMobileNo());
            return newUser;
        }).collect(Collectors.toList());

    }
    @Override
    public List<PcaDocOverview> getAllPcaDocOverview(Long userId) {
        UserMaster existingUser = userRepository.findUserByIdAndActive(userId,true);
        List<UserFileMaster> userFiles = userFileMasterRepository.findUserFileByStatusActive();
        if (userFiles.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> pcaUserIds;
        if(existingUser.getRole().getRoleId().equals(1L)) {

            List<UserMaster> pcaUsers = userRepository.findAllUsersByRole(3L);
            pcaUserIds = pcaUsers.stream().map(UserMaster::getUserId).collect(Collectors.toSet());
        }
        else{
            List<UserMaster> pcaUsers = userRepository.findAllByAssigned(3L, userId);
            pcaUserIds = pcaUsers.stream().map(UserMaster::getUserId).collect(Collectors.toSet());
        }

        Map<Long, UserMaster> userDetailsMap = userRepository.findAllById(pcaUserIds)
                .stream()
                .collect(Collectors.toMap(UserMaster::getUserId, user -> user));

        List<PcaDocOverview> response = new ArrayList<>();

        for (UserFileMaster element : userFiles) {
            if (pcaUserIds.contains(element.getUserId())) {
                PcaDocOverview doc = new PcaDocOverview();
                doc.setDocsName(element.getFileMaster().getFileName());
                doc.setExpiringDate(element.getFileExpiryDate());
                doc.setUserId(element.getUserId());

                UserMaster userDetails = userDetailsMap.get(element.getUserId());
                if (userDetails != null) {
                    doc.setPcaUserName(userDetails.getUserFirstName());
                    doc.setAdminName(userDetails.getAssignedTo() != null ? getAdminName(userDetails.getAssignedTo()) : null);
                }
                response.add(doc);
            }
        }
        return response;
    }

    public String getCpsId(Long userId){
        PcaProfile pcaUser = pcaProfileRepository.findByUserId(userId);
        return pcaUser.getUserCpsId();
    }

    public String getPcaProfilePic(Long userId){
        PcaProfile pcaUser = pcaProfileRepository.findByUserId(userId);
        return pcaUser.getUserProfilePic();
    }

    public Integer getPcaClients(Long userId){
        List<UserMaster> clients = userRepository.findAllClientsForPca(userId , 4L);
        return clients.size();
    }

    public String getAdminName(Long userId){
        UserMaster user = userRepository.findUserById(userId);
        if(Objects.isNull(user)){
            return null;
        }
        String userName;
        if (user.getUserMiddleName() == null || user.getUserMiddleName().isEmpty()) {
            userName = user.getUserFirstName() + " " + user.getUserLastName();
        } else {
            userName = user.getUserFirstName() + " " + user.getUserMiddleName().toCharArray()[0]+"." + " " + user.getUserLastName();
        }
        return userName;
    }

    private PcaProfile getPcaProfile(PcaUserDetails pcaUserDetails, UserMaster userId) {
        PcaProfile pcaProfile = new PcaProfile();
        pcaProfile.setUserState(pcaUserDetails.getState());
        pcaProfile.setCreatedBy(pcaUserDetails.getCreatedBy());
        pcaProfile.setUserId(userId.getUserId());
        pcaProfile.setUserAddress(pcaUserDetails.getUserAddress());
        pcaProfile.setPermissionGranted(false);
        pcaProfile.setUserCity(pcaUserDetails.getCity());
        pcaProfile.setUserCountry(pcaUserDetails.getCountry());
        pcaProfile.setUserCountryCode(pcaUserDetails.getMobileCountryCode());
        pcaProfile.setUserHhdId(pcaUserDetails.getUserHhdId());
        pcaProfile.setUserCpsId(pcaUserDetails.getUserCpsId());
        pcaProfile.setUserHhdId2(pcaUserDetails.getUserHhdId2());
        pcaProfile.setUserPostalCode(pcaUserDetails.getPostalCode());
        return pcaProfile;
    }

    public void validateRoleForSuperAdminRegAdmin(Long value){
        List<Long> validRoles = Arrays.asList(1L, 2L);
        if(!validRoles.contains(value)){
            throw new BadRequestException(Constants.ACCESS_DENIED);
        }
    }
    public String generatePassword() {
        return GeneralUtil.generatePassword();
    }

    @Override
    public Page<PcaListResponse> getAllPcaForAdmin(Pageable pageable , Long id) {
        log.info("Entering all pca list......");
        Page<UserMaster> allUsers = userRepository.findAllByAssignedId(3L, id , pageable);

        if(allUsers.isEmpty()){
            return Page.empty(pageable);
        }


        log.info("Exiting all pca list......");
        return allUsers.map(user -> {
            PcaListResponse pcaUser = new PcaListResponse();
            pcaUser.setUserClients(getPcaClients(user.getUserId()));
            pcaUser.setUserDocs(true);
            String userName;
            if (user.getUserMiddleName() == null || user.getUserMiddleName().isEmpty()) {
                userName = user.getUserFirstName() + " " + user.getUserLastName();
            } else {
                userName = user.getUserFirstName() + " " + user.getUserMiddleName().toCharArray()[0]+"." + " " + user.getUserLastName();
            }
            pcaUser.setUserName(userName);
            pcaUser.setUserMobileNumber(user.getUserMobileNo());
            pcaUser.setUserId(user.getUserId());
            pcaUser.setUserAssignedTo(user.getAssignedTo());
            pcaUser.setUserCpsId(getCpsId(user.getUserId()));
            pcaUser.setCountryCode(user.getUserCountryCode());
            return pcaUser;
        });

    }
}

