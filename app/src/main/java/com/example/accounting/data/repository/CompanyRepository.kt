package com.example.accounting.data.repository

import com.example.accounting.core.common.AccountingResult
import com.example.accounting.core.common.AppError
import com.example.accounting.data.local.dao.AccountingDao
import com.example.accounting.data.local.entity.CompanyEntity
import com.example.accounting.domain.company.Company
import com.example.accounting.domain.company.CompanyStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Interface for Company repository ensuring strict tenant isolation.
 * Every operation explicitly requires companyId.
 */
interface ICompanyRepository {
    fun getAllCompanies(): Flow<List<Company>>
    suspend fun getCompany(companyId: String): AccountingResult<Company>
    suspend fun createCompany(company: Company): AccountingResult<Company>
    suspend fun updateCompany(companyId: String, company: Company): AccountingResult<Company>
    suspend fun deleteCompany(companyId: String): AccountingResult<Unit>
}

class CompanyRepository(
    private val dao: AccountingDao
) : ICompanyRepository {

    override fun getAllCompanies(): Flow<List<Company>> {
        return dao.getAllCompanies().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getCompany(companyId: String): AccountingResult<Company> {
        if (companyId.isBlank()) {
            return AccountingResult.Failure(AppError.ValidationError("companyId must not be blank"))
        }
        val entity = dao.getCompanyById(companyId)
            ?: return AccountingResult.Failure(AppError.ValidationError("Company '$companyId' not found"))
        return AccountingResult.Success(entity.toDomain())
    }

    override suspend fun createCompany(company: Company): AccountingResult<Company> {
        return try {
            val existing = dao.getCompanyById(company.companyId)
            if (existing != null) {
                return AccountingResult.Failure(AppError.ValidationError("Company '${company.companyId}' already exists"))
            }
            dao.insertCompany(company.toEntity())
            AccountingResult.Success(company)
        } catch (e: Throwable) {
            AccountingResult.Failure(AppError.DatabaseError("Failed to create company", e))
        }
    }

    override suspend fun updateCompany(companyId: String, company: Company): AccountingResult<Company> {
        if (companyId != company.companyId) {
            return AccountingResult.Failure(AppError.TenantMismatch(companyId, company.companyId))
        }
        return try {
            dao.updateCompany(company.toEntity())
            AccountingResult.Success(company)
        } catch (e: Throwable) {
            AccountingResult.Failure(AppError.DatabaseError("Failed to update company", e))
        }
    }

    override suspend fun deleteCompany(companyId: String): AccountingResult<Unit> {
        if (companyId.isBlank()) {
            return AccountingResult.Failure(AppError.ValidationError("companyId must not be blank"))
        }
        return try {
            val existing = dao.getCompanyById(companyId)
                ?: return AccountingResult.Failure(AppError.ValidationError("Company '$companyId' not found"))
            // Deleting the last remaining company is explicitly allowed (product decision) - the
            // UI is responsible for warning the user first, not this layer.
            dao.deleteCompany(companyId)
            // If the deleted company held isDefault, promote another remaining company so a
            // default always exists for getDefaultCompany()/app-launch company resolution.
            if (existing.isDefault) {
                dao.getAllCompaniesSnapshot().firstOrNull()?.let { replacement ->
                    dao.updateCompany(replacement.copy(isDefault = true))
                }
            }
            AccountingResult.Success(Unit)
        } catch (e: Throwable) {
            AccountingResult.Failure(AppError.DatabaseError("Failed to delete company", e))
        }
    }

    private fun CompanyEntity.toDomain(): Company {
        return Company(
            companyId = companyId,
            name = name,
            legalName = name,
            tradeName = tradeName,
            gstin = gstin,
            pan = pan,
            stateCode = stateCode,
            stateName = stateName,
            email = email,
            phone = phone,
            address = address,
            currency = currency,
            financialYearStartMonth = financialYearStartMonth,
            status = CompanyStatus.ACTIVE,
            isDefault = isDefault,
            createdAt = createdAt,
            updatedAt = createdAt,
            pinCode = pinCode
        )
    }

    private fun Company.toEntity(): CompanyEntity {
        return CompanyEntity(
            companyId = companyId,
            name = name,
            tradeName = tradeName,
            gstin = gstin,
            pan = pan,
            stateCode = stateCode,
            stateName = stateName,
            email = email,
            phone = phone,
            address = address,
            currency = currency,
            financialYearStartMonth = financialYearStartMonth,
            isDefault = isDefault,
            createdAt = createdAt,
            pinCode = pinCode
        )
    }
}
