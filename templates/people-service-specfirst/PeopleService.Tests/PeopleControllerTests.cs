using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging.Abstractions;
using PeopleService.WebApi.Controllers;
using PeopleService.WebApi.Directory;
using Xunit;

namespace PeopleService.Tests;

// Unit tests for the people lookup controller — no host, no HTTP, no directory file. The directory
// is a hand fake. The value here is the request-validation surface: the account-service upstream
// calls GetPerson to decide whether a username is real, so the 400-vs-404-vs-200 boundaries are a
// security-relevant contract. A missing-id request must be rejected, and an unknown person must be
// a 404 — never a 200 that reads as "valid".
public class PeopleControllerTests
{
    private sealed class FakeDirectory : IDirectoryService
    {
        public Person? PersonResult;
        public List<Person> MatchResult = new();
        public bool ValidResult;

        public Task<Person?> GetPersonAsync(string? logonId, string? employeeId) => Task.FromResult(PersonResult);
        public Task<List<Person>> GetMatchingPeopleAsync(string searchText, int take) => Task.FromResult(MatchResult);
        public Task<bool> ValidatePersonAsync(string? logonId, string? employeeId) => Task.FromResult(ValidResult);
    }

    private static PeopleController Controller(FakeDirectory dir) =>
        new(dir, NullLogger<PeopleController>.Instance);

    private static readonly Person Alice = new() { LogonId = "user01", FullName = "Alice Johnson", EmployeeId = "E0001" };

    // --- GetPerson ---

    [Fact]
    public async Task GetPerson_withNeitherId_isBadRequest()
    {
        var result = await Controller(new FakeDirectory()).GetPerson(null, null);
        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task GetPerson_whenDirectoryHasNoMatch_isNotFound()
    {
        var result = await Controller(new FakeDirectory { PersonResult = null }).GetPerson("ghost", null);
        Assert.IsType<NotFoundResult>(result);
    }

    [Fact]
    public async Task GetPerson_whenFound_isOkWithPerson()
    {
        var result = await Controller(new FakeDirectory { PersonResult = Alice }).GetPerson("user01", null);
        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.Same(Alice, ok.Value);
    }

    // --- GetMatchingPeople ---

    [Fact]
    public async Task GetMatchingPeople_withNoSearchText_isBadRequest()
    {
        var result = await Controller(new FakeDirectory()).GetMatchingPeople(null);
        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task GetMatchingPeople_withSearchTextUnderThreeChars_isBadRequest()
    {
        var result = await Controller(new FakeDirectory()).GetMatchingPeople("ab");
        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task GetMatchingPeople_withNoMatches_isNotFound()
    {
        var result = await Controller(new FakeDirectory { MatchResult = new() }).GetMatchingPeople("alice");
        Assert.IsType<NotFoundResult>(result);
    }

    [Fact]
    public async Task GetMatchingPeople_withMatches_isOkWithWrappedList()
    {
        var dir = new FakeDirectory { MatchResult = new() { Alice } };
        var result = await Controller(dir).GetMatchingPeople("alice");
        var ok = Assert.IsType<OkObjectResult>(result);
        var payload = Assert.IsType<GetMatchingPeopleResponse>(ok.Value);
        Assert.Single(payload.People);
    }

    // --- ValidatePerson ---

    [Fact]
    public async Task ValidatePerson_withNeitherId_isBadRequest()
    {
        var result = await Controller(new FakeDirectory()).ValidatePerson(null, null);
        Assert.IsType<BadRequestObjectResult>(result);
    }

    [Fact]
    public async Task ValidatePerson_whenInvalid_isNotFound()
    {
        var result = await Controller(new FakeDirectory { ValidResult = false }).ValidatePerson("ghost", null);
        Assert.IsType<NotFoundResult>(result);
    }

    [Fact]
    public async Task ValidatePerson_whenValid_isOk()
    {
        var result = await Controller(new FakeDirectory { ValidResult = true }).ValidatePerson("user01", null);
        Assert.IsType<OkResult>(result);
    }
}
