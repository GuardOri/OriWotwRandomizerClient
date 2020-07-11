#include <macros.h>
#include <interception_macros.h>

namespace
{
    int extra_jumps = 0;
    IL2CPP_INTERCEPT(, SeinDoubleJump, int32_t, get_ExtraJumpsAvailable, (app::SeinDoubleJump* this_ptr)) {
        return extra_jumps + SeinDoubleJump_get_ExtraJumpsAvailable(this_ptr);
    }

    int extra_dashes = 0;
    int dashes_used = 0;
    IL2CPP_INTERCEPT(, SeinDashNew, void, TryPerformDash, (app::SeinDashNew* this_ptr, int32_t direction, bool is_forward)) {
        SeinDashNew_TryPerformDash(this_ptr, direction, is_forward);
        if (this_ptr->fields.m_isDashing)
            ++dashes_used;
    }

    IL2CPP_INTERCEPT(, SeinDashNew, void, UpdateAllowDashFlag, (app::SeinDashNew* this_ptr)) {
        this_ptr->fields.m_allowDash = false;
        SeinDashNew_UpdateAllowDashFlag(this_ptr);
        if (this_ptr->fields.m_allowDash)
            dashes_used = 0;
        else
            this_ptr->fields.m_allowDash = dashes_used <= extra_dashes;
    }
}

INJECT_C_DLLEXPORT void set_extra_jumps(int value)
{
    extra_jumps = value;
}

INJECT_C_DLLEXPORT void set_extra_dashes(int value)
{
    extra_dashes = value;
}